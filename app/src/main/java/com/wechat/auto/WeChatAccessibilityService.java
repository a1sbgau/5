package com.wechat.auto;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.nio.ByteBuffer;
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
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    @Override
    public void onCreate() {
        super.onCreate();
        configManager = new ConfigManager(this);
        
        // 获取屏幕尺寸
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Display display = wm.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            screenDensity = metrics.densityDpi;
            Log.i(TAG, "屏幕尺寸: " + screenWidth + "x" + screenHeight + ", DPI: " + screenDensity);
        }
        
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
     * 自动接听视频通话 - 增强版
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
                    if (tryClickNode(button, "接听按钮")) {
                        return;
                    }
                }
            }
            
            // 方法2: 通过 View ID 查找
            String[] viewIds = {
                "com.tencent.mm:id/accept_btn",
                "com.tencent.mm:id/btn_accept",
                "com.tencent.mm:id/voip_accept_btn",
                "com.tencent.mm:id/video_accept_btn",
                "com.tencent.mm:id/answer_btn",
                "com.tencent.mm:id/btn_answer"
            };
            
            for (String viewId : viewIds) {
                List<AccessibilityNodeInfo> buttons = rootNode.findAccessibilityNodeInfosByViewId(viewId);
                if (!buttons.isEmpty()) {
                    Log.i(TAG, "找到接听按钮（ID: " + viewId + "）");
                    for (AccessibilityNodeInfo button : buttons) {
                        if (tryClickNode(button, "ID按钮")) {
                            return;
                        }
                    }
                }
            }
            
            // 方法3: 智能查找 - 通过位置和大小判断（绿色按钮通常在屏幕下方中间）
            List<AccessibilityNodeInfo> allButtons = findAllClickableButtons(rootNode);
            Log.i(TAG, "找到所有可点击按钮: " + allButtons.size() + " 个");
            
            // 获取屏幕尺寸
            android.graphics.Rect screenBounds = new android.graphics.Rect();
            rootNode.getBoundsInScreen(screenBounds);
            int screenHeight = screenBounds.height();
            int screenWidth = screenBounds.width();
            
            Log.i(TAG, "屏幕尺寸: " + screenWidth + "x" + screenHeight);
            
            // 查找位于屏幕下半部分、居中的大按钮（很可能是接听按钮）
            for (AccessibilityNodeInfo button : allButtons) {
                android.graphics.Rect bounds = new android.graphics.Rect();
                button.getBoundsInScreen(bounds);
                
                int buttonCenterY = (bounds.top + bounds.bottom) / 2;
                int buttonCenterX = (bounds.left + bounds.right) / 2;
                int buttonWidth = bounds.width();
                int buttonHeight = bounds.height();
                
                // 判断条件：
                // 1. 在屏幕下半部分（Y > 50%）
                // 2. 水平居中（X 在 30%-70% 之间）
                // 3. 按钮足够大（宽度 > 100px，高度 > 100px）
                boolean inBottomHalf = buttonCenterY > screenHeight * 0.5;
                boolean horizontallyCentered = buttonCenterX > screenWidth * 0.3 && 
                                               buttonCenterX < screenWidth * 0.7;
                boolean largeEnough = buttonWidth > 100 && buttonHeight > 100;
                
                if (inBottomHalf && horizontallyCentered && largeEnough) {
                    CharSequence text = button.getText();
                    CharSequence desc = button.getContentDescription();
                    String className = button.getClassName() != null ? button.getClassName().toString() : "";
                    
                    Log.i(TAG, String.format("找到可能的接听按钮 - 位置: (%d,%d), 大小: %dx%d, 文本: %s, 描述: %s, 类: %s",
                        buttonCenterX, buttonCenterY, buttonWidth, buttonHeight, text, desc, className));
                    
                    if (tryClickNode(button, "位置判断按钮")) {
                        return;
                    }
                }
            }
            
            // 方法4: 查找包含"接"字或相关关键词的按钮
            for (AccessibilityNodeInfo button : allButtons) {
                CharSequence text = button.getText();
                CharSequence desc = button.getContentDescription();
                String textStr = text != null ? text.toString() : "";
                String descStr = desc != null ? desc.toString() : "";
                
                if (textStr.contains("接") || descStr.contains("接") ||
                    textStr.contains("answer") || descStr.contains("answer") ||
                    textStr.contains("Accept") || descStr.contains("Accept")) {
                    
                    Log.i(TAG, "找到可能的接听按钮（关键词）: " + textStr + " / " + descStr);
                    if (tryClickNode(button, "关键词按钮")) {
                        return;
                    }
                }
            }
            
            // 方法5: 最后尝试 - 点击屏幕下方中间位置（绿色按钮的常见位置）
            Log.i(TAG, "尝试点击屏幕下方中间位置...");
            int clickX = screenWidth / 2;
            int clickY = (int)(screenHeight * 0.75); // 屏幕 75% 高度位置
            
            if (performGlobalClick(clickX, clickY)) {
                Log.i(TAG, "✓ 已点击屏幕位置: (" + clickX + ", " + clickY + ")");
                return;
            }
            
            Log.w(TAG, "所有方法都失败，尝试截图识别...");
            // 打印界面信息用于调试
            printNodeInfo(rootNode, 0);
            
            // 最后尝试：截图识别绿色按钮（Android 11+）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                handler.postDelayed(() -> takeScreenshotAndFindGreenButton(), 500);
            } else {
                Log.w(TAG, "截图功能需要 Android 11+，当前版本: " + android.os.Build.VERSION.SDK_INT);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "自动接听异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            rootNode.recycle();
        }
    }
    
    /**
     * 尝试点击节点（包括父节点）
     */
    private boolean tryClickNode(AccessibilityNodeInfo node, String source) {
        if (node == null) return false;
        
        // 尝试直接点击
        if (node.isClickable()) {
            boolean success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "✓ 点击" + source + ": " + success);
            if (success) return true;
        }
        
        // 尝试点击父节点
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null && parent.isClickable()) {
            boolean success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "✓ 点击" + source + "父节点: " + success);
            if (success) return true;
        }
        
        // 尝试通过坐标点击
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        int centerX = (bounds.left + bounds.right) / 2;
        int centerY = (bounds.top + bounds.bottom) / 2;
        
        if (performGlobalClick(centerX, centerY)) {
            Log.i(TAG, "✓ 通过坐标点击" + source + ": (" + centerX + ", " + centerY + ")");
            return true;
        }
        
        return false;
    }
    
    /**
     * 通过全局坐标点击（需要 Android 7.0+）
     */
    private boolean performGlobalClick(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(x, y);
                
                android.accessibilityservice.GestureDescription.StrokeDescription stroke = 
                    new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100);
                
                android.accessibilityservice.GestureDescription gesture = 
                    new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();
                
                return dispatchGesture(gesture, null, null);
            } catch (Exception e) {
                Log.e(TAG, "全局点击失败: " + e.getMessage());
            }
        }
        return false;
    }
    
    /**
     * 查找所有可点击的按钮
     */
    private List<AccessibilityNodeInfo> findAllClickableButtons(AccessibilityNodeInfo node) {
        List<AccessibilityNodeInfo> buttons = new java.util.ArrayList<>();
        if (node == null) return buttons;
        
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        
        // 只收集按钮类型的节点
        if (node.isClickable() && (
            className.contains("Button") || 
            className.contains("ImageView") ||
            className.contains("TextView"))) {
            buttons.add(node);
        }
        
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                buttons.addAll(findAllClickableButtons(child));
            }
        }
        
        return buttons;
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
     * 截图并识别绿色按钮位置
     */
    private void takeScreenshotAndFindGreenButton() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            Log.w(TAG, "截图功能需要 Android 11+");
            return;
        }
        
        try {
            Log.i(TAG, "开始截图识别绿色按钮...");
            
            // 使用无障碍服务的截图功能
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), 
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshotResult) {
                        try {
                            Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.getHardwareBuffer(),
                                screenshotResult.getColorSpace()
                            );
                            
                            if (bitmap != null) {
                                Log.i(TAG, "截图成功: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                                
                                // 分析图像找绿色按钮
                                android.graphics.Point greenButtonPos = findGreenButton(bitmap);
                                
                                if (greenButtonPos != null) {
                                    Log.i(TAG, "找到绿色按钮位置: (" + greenButtonPos.x + ", " + greenButtonPos.y + ")");
                                    performGlobalClick(greenButtonPos.x, greenButtonPos.y);
                                } else {
                                    Log.w(TAG, "未找到绿色按钮");
                                }
                                
                                bitmap.recycle();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "处理截图失败: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                    
                    @Override
                    public void onFailure(int errorCode) {
                        Log.e(TAG, "截图失败，错误码: " + errorCode);
                    }
                });
                
        } catch (Exception e) {
            Log.e(TAG, "截图异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 分析图像找绿色按钮
     */
    private android.graphics.Point findGreenButton(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            
            Log.i(TAG, "开始分析图像，尺寸: " + width + "x" + height);
            
            // 只扫描屏幕下半部分（50%-90%）
            int startY = height / 2;
            int endY = (int)(height * 0.9);
            
            // 扫描步长（每隔10个像素扫描一次，提高速度）
            int step = 10;
            
            int maxGreenScore = 0;
            android.graphics.Point bestPosition = null;
            
            // 扫描图像
            for (int y = startY; y < endY; y += step) {
                for (int x = 0; x < width; x += step) {
                    int pixel = bitmap.getPixel(x, y);
                    
                    // 提取 RGB 值
                    int red = Color.red(pixel);
                    int green = Color.green(pixel);
                    int blue = Color.blue(pixel);
                    
                    // 判断是否为绿色
                    // 绿色特征：G > R 且 G > B，且 G 值较高
                    if (green > red + 30 && green > blue + 30 && green > 100) {
                        // 计算绿色得分
                        int greenScore = green - Math.max(red, blue);
                        
                        if (greenScore > maxGreenScore) {
                            maxGreenScore = greenScore;
                            bestPosition = new android.graphics.Point(x, y);
                        }
                    }
                }
            }
            
            if (bestPosition != null && maxGreenScore > 50) {
                Log.i(TAG, "找到绿色区域，得分: " + maxGreenScore + ", 位置: (" + bestPosition.x + ", " + bestPosition.y + ")");
                
                // 在绿色区域周围寻找更精确的中心点
                android.graphics.Point center = findGreenCenter(bitmap, bestPosition.x, bestPosition.y);
                return center != null ? center : bestPosition;
            }
            
            Log.w(TAG, "未找到明显的绿色区域");
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "分析图像异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 在绿色区域周围找到中心点
     */
    private android.graphics.Point findGreenCenter(Bitmap bitmap, int startX, int startY) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            
            // 在起始点周围 100x100 的区域内寻找绿色中心
            int searchRadius = 50;
            int minX = Math.max(0, startX - searchRadius);
            int maxX = Math.min(width - 1, startX + searchRadius);
            int minY = Math.max(0, startY - searchRadius);
            int maxY = Math.min(height - 1, startY + searchRadius);
            
            int totalX = 0;
            int totalY = 0;
            int greenCount = 0;
            
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    int pixel = bitmap.getPixel(x, y);
                    int red = Color.red(pixel);
                    int green = Color.green(pixel);
                    int blue = Color.blue(pixel);
                    
                    if (green > red + 30 && green > blue + 30 && green > 100) {
                        totalX += x;
                        totalY += y;
                        greenCount++;
                    }
                }
            }
            
            if (greenCount > 0) {
                int centerX = totalX / greenCount;
                int centerY = totalY / greenCount;
                Log.i(TAG, "绿色中心点: (" + centerX + ", " + centerY + "), 绿色像素数: " + greenCount);
                return new android.graphics.Point(centerX, centerY);
            }
            
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "查找绿色中心异常: " + e.getMessage());
            return null;
        }
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
