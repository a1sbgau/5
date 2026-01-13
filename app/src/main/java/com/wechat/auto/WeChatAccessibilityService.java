package com.wechat.auto;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WeChatAccessibilityService extends AccessibilityService {
    
    private static final String TAG = "WeChatAutoService";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    
    private ConfigManager configManager;
    private Map<String, Long> lastSendTime = new HashMap<>();
    private String lastProcessedMessage = "";
    private Handler handler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onCreate() {
        super.onCreate();
        configManager = new ConfigManager(this);
        Log.i(TAG, "服务已创建");
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!WECHAT_PACKAGE.equals(packageName)) return;
        
        int eventType = event.getEventType();
        
        switch (eventType) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                handleWindowStateChanged(event);
                break;
                
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                handleWindowContentChanged(event);
                break;
                
            case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED:
                handleNotification(event);
                break;
        }
    }
    
    /**
     * 处理窗口状态变化 - 检测视频通话
     */
    private void handleWindowStateChanged(AccessibilityEvent event) {
        String className = event.getClassName() != null ? event.getClassName().toString() : "";
        Log.d(TAG, "窗口变化: " + className);
        
        // 检测视频通话界面 - 扩展更多可能的类名
        if (className.contains("Voip") || 
            className.contains("Video") || 
            className.contains("voip") ||
            className.contains("video") ||
            className.contains("Call") ||
            className.contains("call")) {
            
            if (configManager.isAutoAnswerEnabled()) {
                Log.i(TAG, "检测到可能的通话界面: " + className);
                // 延迟一下，等待界面完全加载
                handler.postDelayed(() -> {
                    Log.i(TAG, "尝试自动接听...");
                    performAutoAnswer();
                }, 500);
            }
        }
        
        // 额外检查：直接查找接听按钮
        if (configManager.isAutoAnswerEnabled()) {
            handler.postDelayed(() -> {
                AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                if (rootNode != null) {
                    try {
                        // 快速检查是否有接听按钮
                        List<AccessibilityNodeInfo> answerButtons = rootNode.findAccessibilityNodeInfosByText("接听");
                        if (!answerButtons.isEmpty()) {
                            Log.i(TAG, "发现接听按钮，立即处理");
                            performAutoAnswer();
                        }
                    } finally {
                        rootNode.recycle();
                    }
                }
            }, 300);
        }
    }
    
    /**
     * 处理窗口内容变化 - 检测新消息
     */
    private void handleWindowContentChanged(AccessibilityEvent event) {
        if (!configManager.isAutoReplyEnabled()) return;
        
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;
        
        try {
            // 检测聊天界面
            String chatTitle = getChatTitle(rootNode);
            if (chatTitle != null) {
                // 获取最新消息
                String latestMessage = getLatestMessage(rootNode);
                if (latestMessage != null && !latestMessage.equals(lastProcessedMessage)) {
                    lastProcessedMessage = latestMessage;
                    Log.i(TAG, "收到消息: " + latestMessage);
                    
                    // 检查关键词并回复
                    checkAndReply(chatTitle, latestMessage, rootNode);
                }
            }
        } finally {
            rootNode.recycle();
        }
    }
    
    /**
     * 处理通知 - 检测来电
     */
    private void handleNotification(AccessibilityEvent event) {
        if (!configManager.isAutoAnswerEnabled()) return;
        
        List<CharSequence> texts = event.getText();
        Log.d(TAG, "收到通知，文本数量: " + texts.size());
        
        for (CharSequence text : texts) {
            String content = text.toString();
            Log.d(TAG, "通知内容: " + content);
            
            // 扩展检测关键词
            if (content.contains("视频通话") || 
                content.contains("语音通话") ||
                content.contains("视频聊天") ||
                content.contains("来电") ||
                content.contains("呼叫") ||
                content.toLowerCase().contains("video") ||
                content.toLowerCase().contains("call")) {
                
                Log.i(TAG, "检测到通话通知: " + content);
                // 延迟处理，等待界面打开
                handler.postDelayed(() -> {
                    Log.i(TAG, "通知触发，尝试接听...");
                    performAutoAnswer();
                }, 1500);
                break;
            }
        }
    }
    
    /**
     * 自动接听视频通话
     */
    private void performAutoAnswer() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.w(TAG, "无法获取根节点");
            return;
        }
        
        try {
            Log.i(TAG, "开始查找接听按钮...");
            
            // 方法1: 通过文本查找 "接听"
            List<AccessibilityNodeInfo> answerButtons = rootNode.findAccessibilityNodeInfosByText("接听");
            if (!answerButtons.isEmpty()) {
                Log.i(TAG, "找到接听按钮（文本）: " + answerButtons.size() + " 个");
                for (AccessibilityNodeInfo button : answerButtons) {
                    if (button.isClickable()) {
                        boolean success = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.i(TAG, "✓ 点击接听按钮: " + success);
                        if (success) return;
                    }
                    // 尝试点击父节点
                    AccessibilityNodeInfo parent = button.getParent();
                    if (parent != null && parent.isClickable()) {
                        boolean success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.i(TAG, "✓ 点击接听按钮父节点: " + success);
                        if (success) return;
                    }
                }
            }
            
            // 方法2: 通过 View ID 查找
            String[] viewIds = {
                "com.tencent.mm:id/accept_btn",
                "com.tencent.mm:id/btn_accept",
                "com.tencent.mm:id/voip_accept_btn",
                "com.tencent.mm:id/video_accept_btn"
            };
            
            for (String viewId : viewIds) {
                List<AccessibilityNodeInfo> buttons = rootNode.findAccessibilityNodeInfosByViewId(viewId);
                if (!buttons.isEmpty()) {
                    Log.i(TAG, "找到接听按钮（ID: " + viewId + "）");
                    for (AccessibilityNodeInfo button : buttons) {
                        if (button.isClickable()) {
                            boolean success = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.i(TAG, "✓ 点击接听按钮: " + success);
                            if (success) return;
                        }
                    }
                }
            }
            
            // 方法3: 查找包含"接"字的按钮
            List<AccessibilityNodeInfo> buttons = findClickableNodes(rootNode);
            for (AccessibilityNodeInfo button : buttons) {
                CharSequence text = button.getText();
                CharSequence desc = button.getContentDescription();
                String textStr = text != null ? text.toString() : "";
                String descStr = desc != null ? desc.toString() : "";
                
                if (textStr.contains("接") || descStr.contains("接") ||
                    textStr.contains("answer") || descStr.contains("answer")) {
                    Log.i(TAG, "找到可能的接听按钮: " + textStr + " / " + descStr);
                    boolean success = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "✓ 尝试点击: " + success);
                    if (success) return;
                }
            }
            
            Log.w(TAG, "未找到接听按钮");
            // 打印界面信息用于调试
            printNodeInfo(rootNode, 0);
            
        } catch (Exception e) {
            Log.e(TAG, "自动接听异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            rootNode.recycle();
        }
    }
    
    /**
     * 查找所有可点击的节点
     */
    private List<AccessibilityNodeInfo> findClickableNodes(AccessibilityNodeInfo node) {
        List<AccessibilityNodeInfo> clickableNodes = new java.util.ArrayList<>();
        if (node == null) return clickableNodes;
        
        if (node.isClickable()) {
            clickableNodes.add(node);
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                clickableNodes.addAll(findClickableNodes(child));
            }
        }
        
        return clickableNodes;
    }
    
    /**
     * 打印节点信息（用于调试）
     */
    private void printNodeInfo(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 3) return; // 限制深度避免日志过多
        
        String indent = new String(new char[depth * 2]).replace('\0', ' ');
        String text = node.getText() != null ? node.getText().toString() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        String viewId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
        
        if (!text.isEmpty() || !desc.isEmpty() || !viewId.isEmpty()) {
            Log.d(TAG, indent + "Node: text=" + text + ", desc=" + desc + 
                  ", id=" + viewId + ", clickable=" + node.isClickable());
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                printNodeInfo(child, depth + 1);
            }
        }
    }
    
    /**
     * 获取聊天标题
     */
    private String getChatTitle(AccessibilityNodeInfo rootNode) {
        // 尝试多种方式获取标题
        List<AccessibilityNodeInfo> titleNodes = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/title");
        if (!titleNodes.isEmpty()) {
            CharSequence title = titleNodes.get(0).getText();
            if (title != null) {
                return title.toString();
            }
        }
        return null;
    }
    
    /**
     * 获取最新消息
     */
    private String getLatestMessage(AccessibilityNodeInfo rootNode) {
        // 查找消息列表
        List<AccessibilityNodeInfo> messageNodes = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/chatting_content_layout");
        
        if (messageNodes.isEmpty()) {
            // 尝试其他方式
            messageNodes = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/message_content");
        }
        
        if (!messageNodes.isEmpty()) {
            // 获取最后一条消息
            AccessibilityNodeInfo lastMsg = messageNodes.get(messageNodes.size() - 1);
            return extractText(lastMsg);
        }
        
        return null;
    }
    
    /**
     * 提取节点文本
     */
    private String extractText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            return text.toString();
        }
        
        // 递归查找子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                String childText = extractText(child);
                if (childText != null) {
                    return childText;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 检查关键词并回复
     */
    private void checkAndReply(String chatTitle, String message, AccessibilityNodeInfo rootNode) {
        // 检查冷却时间
        long now = System.currentTimeMillis();
        Long lastTime = lastSendTime.get(chatTitle);
        int cooldown = configManager.getCooldownSeconds() * 1000;
        
        if (lastTime != null && (now - lastTime) < cooldown) {
            Log.d(TAG, "冷却中，跳过");
            return;
        }
        
        // 检查关键词
        String reply = configManager.checkKeyword(message);
        if (reply != null) {
            Log.i(TAG, "触发关键词，准备回复: " + reply);
            
            if (sendMessage(reply, rootNode)) {
                lastSendTime.put(chatTitle, now);
                Log.i(TAG, "✓ 回复成功");
            } else {
                Log.e(TAG, "✗ 回复失败");
            }
        }
    }
    
    /**
     * 发送消息
     */
    private boolean sendMessage(String message, AccessibilityNodeInfo rootNode) {
        try {
            // 查找输入框
            List<AccessibilityNodeInfo> editNodes = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/input");
            
            if (editNodes.isEmpty()) {
                editNodes = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/edit_text");
            }
            
            if (editNodes.isEmpty()) {
                Log.e(TAG, "未找到输入框");
                return false;
            }
            
            AccessibilityNodeInfo editNode = editNodes.get(0);
            
            // 点击输入框
            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            
            // 输入文本
            android.os.Bundle arguments = new android.os.Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message);
            editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            
            // 等待一下
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            
            // 查找发送按钮
            List<AccessibilityNodeInfo> sendButtons = rootNode.findAccessibilityNodeInfosByText("发送");
            if (sendButtons.isEmpty()) {
                sendButtons = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/send_btn");
            }
            
            if (!sendButtons.isEmpty()) {
                AccessibilityNodeInfo sendBtn = sendButtons.get(0);
                sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            
            Log.e(TAG, "未找到发送按钮");
            return false;
            
        } catch (Exception e) {
            Log.e(TAG, "发送消息异常", e);
            return false;
        }
    }
    
    @Override
    public void onInterrupt() {
        Log.w(TAG, "服务中断");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "服务已销毁");
    }
    
    /**
     * 检查服务是否启用
     */
    public static boolean isServiceEnabled(Context context) {
        String service = context.getPackageName() + "/" + WeChatAccessibilityService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        return enabledServices != null && enabledServices.contains(service);
    }
}
