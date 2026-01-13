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
        
        // 检测视频通话界面
        if (className.contains("VoipActivity") || className.contains("VideoActivity")) {
            if (configManager.isAutoAnswerEnabled()) {
                Log.i(TAG, "检测到视频通话，尝试自动接听");
                performAutoAnswer();
            }
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
        for (CharSequence text : texts) {
            String content = text.toString();
            if (content.contains("视频通话") || content.contains("语音通话")) {
                Log.i(TAG, "检测到通话通知");
                // 延迟处理，等待界面打开
                handler.postDelayed(() -> performAutoAnswer(), 1000);
                break;
            }
        }
    }
    
    /**
     * 自动接听视频通话
     */
    private void performAutoAnswer() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;
        
        try {
            // 查找接听按钮
            List<AccessibilityNodeInfo> answerButtons = rootNode.findAccessibilityNodeInfosByText("接听");
            if (answerButtons.isEmpty()) {
                answerButtons = rootNode.findAccessibilityNodeInfosByViewId("com.tencent.mm:id/accept_btn");
            }
            
            for (AccessibilityNodeInfo button : answerButtons) {
                if (button.isClickable()) {
                    button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "✓ 已自动接听");
                    return;
                }
            }
            
            Log.w(TAG, "未找到接听按钮");
        } finally {
            rootNode.recycle();
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
