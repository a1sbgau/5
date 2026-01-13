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
import android.os.Build;
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
    
    // 截图错误码常量（Android 11+）
    private static final int ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR = 1;
    private static final int ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS = 2;
    private static final int ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT = 3;
    
    private ConfigManager configManager;
    private Map<String, Long> lastSendTime = new HashMap<>();
    private String lastProcessedMessage = "";
    private Handler handler = new Handler(Looper.getMainLooper());
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    private boolean isWeChatInForeground = false;
    private Runnable periodicCheckRunnable;
    
    @Override
    public void onCreate() {
        super.onCreate();
        configManager = new ConfigManager(this);
        
        // 执行系统诊断
        performSystemDiagnosis();
        
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
            LogManager.log("✓ 服务已启动");
            LogManager.log("屏幕: " + screenWidth + "x" + screenHeight);
        }
        
        // 初始化周期性检查
        periodicCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (isWeChatInForeground && configManager.isAutoAnswerEnabled()) {
                    checkForAnswerButton();
                }
                // 每2秒检查一次
                handler.postDelayed(this, 2000);
            }
        };
        handler.postDelayed(periodicCheckRunnable, 5000); // 5秒后开始检查
        
        // 10秒后执行功能测试
        handler.postDelayed(() -> {
            LogManager.log("=== 开始功能测试 ===");
            testClickFunction();
            handler.postDelayed(() -> testScreenshotFunction(), 2000);
        }, 10000);
        
        Log.i(TAG, "✓ 无障碍服务已启动");
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
        LogManager.log("窗口: " + className);
        
        // 标记微信是否在前台
        isWeChatInForeground = true;
        
        if (!configManager.isAutoAnswerEnabled()) {
            LogManager.log("⚠ 自动接听功能已关闭");
            return;
        }
        
        // 不再依赖类名判断，只要是微信应用就尝试查找接听按钮
        LogManager.log("→ 检查是否有接听按钮...");
        
        // 延迟一下，等待界面完全加载
        handler.postDelayed(() -> {
            checkForAnswerButton();
        }, 300);
    }
    
    /**
     * 处理窗口内容变化 - 检测新消息和接听按钮
     */
    private void handleWindowContentChanged(AccessibilityEvent event) {
        // 优先检查自动接听（视频通话按钮可能在内容变化时出现）
        if (configManager.isAutoAnswerEnabled()) {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                try {
                    // 快速检查是否有接听按钮
                    List<AccessibilityNodeInfo> answerButtons = rootNode.findAccessibilityNodeInfosByText("接听");
                    if (!answerButtons.isEmpty()) {
                        Log.i(TAG, "内容变化检测到接听按钮");
                        LogManager.log("✓ 内容变化检测到接听按钮");
                        performAutoAnswer();
                        return; // 找到接听按钮就不再处理消息
                    }
                } finally {
                    rootNode.recycle();
                }
            }
        }
        
        // 处理自动回复
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
     * 检查是否有接听按钮（独立方法，可被周期性调用）
     */
    private void checkForAnswerButton() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            return;
        }
        
        try {
            // 方法1: 快速检查是否有接听按钮文本
            List<AccessibilityNodeInfo> answerButtons = rootNode.findAccessibilityNodeInfosByText("接听");
            if (!answerButtons.isEmpty()) {
                Log.i(TAG, "发现接听按钮，立即处理");
                LogManager.log("✓ 发现接听按钮！");
                performAutoAnswer();
                return;
            }
            
            // 方法2: 查找其他可能的文本
            List<AccessibilityNodeInfo> acceptButtons = rootNode.findAccessibilityNodeInfosByText("接受");
            if (!acceptButtons.isEmpty()) {
                LogManager.log("✓ 发现接受按钮！");
                performAutoAnswer();
                return;
            }
            
            // 方法3: 查找包含"接"字的任何文本
            List<AccessibilityNodeInfo> jiButtons = rootNode.findAccessibilityNodeInfosByText("接");
            if (!jiButtons.isEmpty()) {
                Log.i(TAG, "发现包含'接'的按钮: " + jiButtons.size() + "个");
                for (AccessibilityNodeInfo btn : jiButtons) {
                    CharSequence text = btn.getText();
                    if (text != null && (text.toString().contains("接听") || text.toString().equals("接"))) {
                        LogManager.log("✓ 发现'接'按钮！");
                        performAutoAnswer();
                        return;
                    }
                }
            }
            
            // 方法4: 扫描屏幕下半部分的大按钮（可能是接听按钮）
            List<AccessibilityNodeInfo> allButtons = findAllClickableButtons(rootNode);
            if (!allButtons.isEmpty()) {
                android.graphics.Rect screenBounds = new android.graphics.Rect();
                rootNode.getBoundsInScreen(screenBounds);
                int screenHeight = screenBounds.height();
                int screenWidth = screenBounds.width();
                
                for (AccessibilityNodeInfo button : allButtons) {
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    button.getBoundsInScreen(bounds);
                    
                    int buttonCenterY = (bounds.top + bounds.bottom) / 2;
                    int buttonCenterX = (bounds.left + bounds.right) / 2;
                    int buttonWidth = bounds.width();
                    int buttonHeight = bounds.height();
                    
                    // 在屏幕下半部分、居中、足够大的按钮
                    boolean inBottomHalf = buttonCenterY > screenHeight * 0.5;
                    boolean horizontallyCentered = buttonCenterX > screenWidth * 0.3 && 
                                                   buttonCenterX < screenWidth * 0.7;
                    boolean largeEnough = buttonWidth > 100 && buttonHeight > 100;
                    
                    if (inBottomHalf && horizontallyCentered && largeEnough) {
                        CharSequence text = button.getText();
                        CharSequence desc = button.getContentDescription();
                        
                        // 如果没有文本或描述，可能就是接听按钮（通常是图标按钮）
                        if ((text == null || text.length() == 0) && (desc == null || desc.length() == 0)) {
                            Log.i(TAG, "发现可疑的大按钮（无文本）在屏幕下方中间");
                            LogManager.log("→ 发现可疑按钮，尝试点击");
                            if (tryClickNode(button, "可疑大按钮")) {
                                return;
                            }
                        }
                    }
                }
            }
            
        } finally {
            rootNode.recycle();
        }
    }
    
    /**
     * 自动接听视频通话 - 增强版
     */
    private void performAutoAnswer() {
        LogManager.log("→ 开始查找接听按钮");
        
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.w(TAG, "无法获取根节点");
            LogManager.log("✗ 无法获取根节点");
            return;
        }
        
        try {
            Log.i(TAG, "开始查找接听按钮...");
            
            // 方法1: 通过文本查找 "接听"
            LogManager.log("方法1: 查找文本'接听'");
            List<AccessibilityNodeInfo> answerButtons = rootNode.findAccessibilityNodeInfosByText("接听");
            if (!answerButtons.isEmpty()) {
                Log.i(TAG, "找到接听按钮（文本）: " + answerButtons.size() + " 个");
                LogManager.log("✓ 找到 " + answerButtons.size() + " 个接听按钮");
                for (AccessibilityNodeInfo button : answerButtons) {
                    if (tryClickNode(button, "接听按钮")) {
                        LogManager.log("✓✓✓ 成功点击接听按钮！");
                        return;
                    }
                }
            } else {
                LogManager.log("✗ 未找到文本'接听'");
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
        
        LogManager.log("尝试点击: " + source);
        
        // 获取节点的屏幕坐标
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        int centerX = (bounds.left + bounds.right) / 2;
        int centerY = (bounds.top + bounds.bottom) / 2;
        
        LogManager.log("按钮位置: (" + centerX + "," + centerY + ")");
        LogManager.log("按钮大小: " + bounds.width() + "x" + bounds.height());
        
        // 等待一小段时间确保界面稳定
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            // 忽略
        }
        
        // 优先使用坐标点击（更可靠）
        if (performGlobalClick(centerX, centerY)) {
            Log.i(TAG, "✓ 通过坐标点击" + source + ": (" + centerX + ", " + centerY + ")");
            LogManager.log("✓✓✓ 坐标点击成功！");
            return true;
        }
        
        // 备用：尝试直接点击节点
        if (node.isClickable()) {
            boolean success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "✓ 点击" + source + ": " + success);
            LogManager.log("节点点击: " + (success ? "成功" : "失败"));
            if (success) {
                return true;
            }
        }
        
        // 尝试点击父节点
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            android.graphics.Rect parentBounds = new android.graphics.Rect();
            parent.getBoundsInScreen(parentBounds);
            int parentX = (parentBounds.left + parentBounds.right) / 2;
            int parentY = (parentBounds.top + parentBounds.bottom) / 2;
            
            LogManager.log("尝试父节点: (" + parentX + "," + parentY + ")");
            if (performGlobalClick(parentX, parentY)) {
                LogManager.log("✓ 父节点坐标点击成功！");
                return true;
            }
        }
        
        LogManager.log("✗ 所有点击方法都失败");
        return false;
    }
    
    /**
     * 通过全局坐标点击（需要 Android 7.0+）- 增强版
     */
    private boolean performGlobalClick(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                LogManager.log("准备执行增强手势点击: (" + x + "," + y + ")");
                
                // 等待界面完全稳定
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    // 忽略
                }
                
                // 尝试多种手势参数组合
                int[][] gestureParams = {
                    {x, y, 200},        // 标准点击200ms
                    {x, y, 400},        // 长按400ms  
                    {x, y, 100},        // 快速点击100ms
                    {x-2, y-2, 300},    // 稍微偏移点击
                    {x+2, y+2, 300}     // 稍微偏移点击
                };
                
                for (int i = 0; i < gestureParams.length; i++) {
                    final int attempt = i + 1;
                    int clickX = gestureParams[i][0];
                    int clickY = gestureParams[i][1];
                    int duration = gestureParams[i][2];
                    
                    LogManager.log("尝试手势 " + attempt + "/5: (" + clickX + "," + clickY + ") 时长=" + duration + "ms");
                    
                    android.graphics.Path path = new android.graphics.Path();
                    path.moveTo(clickX, clickY);
                    
                    android.accessibilityservice.GestureDescription.StrokeDescription stroke = 
                        new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration);
                    
                    android.accessibilityservice.GestureDescription gesture = 
                        new android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(stroke)
                            .build();
                    
                    final boolean[] gestureCompleted = {false};
                    final boolean[] gestureSucceeded = {false};
                    
                    boolean result = dispatchGesture(gesture, new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(android.accessibilityservice.GestureDescription gestureDescription) {
                            super.onCompleted(gestureDescription);
                            gestureCompleted[0] = true;
                            gestureSucceeded[0] = true;
                            LogManager.log("✓ 手势 " + attempt + " 执行完成");
                        }
                        
                        @Override
                        public void onCancelled(android.accessibilityservice.GestureDescription gestureDescription) {
                            super.onCancelled(gestureDescription);
                            gestureCompleted[0] = true;
                            gestureSucceeded[0] = false;
                            LogManager.log("✗ 手势 " + attempt + " 被取消");
                        }
                    }, null);
                    
                    LogManager.log("手势 " + attempt + " 分发结果: " + result);
                    
                    if (result) {
                        // 等待手势完成
                        int waitCount = 0;
                        while (!gestureCompleted[0] && waitCount < 20) { // 最多等待2秒
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                break;
                            }
                            waitCount++;
                        }
                        
                        if (gestureSucceeded[0]) {
                            LogManager.log("✓✓✓ 手势点击成功！");
                            
                            // 成功后短暂等待，然后再次确认点击
                            handler.postDelayed(() -> {
                                LogManager.log("执行确认点击");
                                performSimpleClick(x, y);
                            }, 500);
                            
                            return true;
                        }
                    }
                    
                    // 每次尝试之间等待
                    if (i < gestureParams.length - 1) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
                
                LogManager.log("所有手势尝试失败，使用备用方法");
                return performBackupClick(x, y);
                
            } catch (Exception e) {
                Log.e(TAG, "增强手势点击失败: " + e.getMessage());
                LogManager.log("✗ 增强手势点击异常: " + e.getMessage());
                e.printStackTrace();
                return performBackupClick(x, y);
            }
        } else {
            LogManager.log("✗ Android 版本过低，不支持手势点击");
            return false;
        }
    }
    
    /**
     * 备用点击方法
     */
    private boolean performBackupClick(int x, int y) {
        LogManager.log("执行备用点击: (" + x + "," + y + ")");
        
        // 方法1: 尝试Shell命令点击（需要root权限）
        if (tryShellClick(x, y)) {
            LogManager.log("✓ Shell命令点击成功");
            return true;
        }
        
        // 方法2: 尝试多次短手势点击
        try {
            for (int i = 0; i < 5; i++) {
                final int attemptNumber = i + 1; // Create a final copy for use in inner class
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(x, y);
                
                // 尝试不同的点击时长
                int duration = attemptNumber * 100; // 100ms, 200ms, 300ms, 400ms, 500ms
                android.accessibilityservice.GestureDescription.StrokeDescription stroke = 
                    new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration);
                
                android.accessibilityservice.GestureDescription gesture = 
                    new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();
                
                boolean result = dispatchGesture(gesture, new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(android.accessibilityservice.GestureDescription gestureDescription) {
                        LogManager.log("✓ 备用手势 " + attemptNumber + " 完成");
                    }
                    
                    @Override
                    public void onCancelled(android.accessibilityservice.GestureDescription gestureDescription) {
                        LogManager.log("✗ 备用手势 " + attemptNumber + " 取消");
                    }
                }, null);
                
                LogManager.log("备用点击 " + attemptNumber + "/5 (时长" + duration + "ms): " + result);
                
                if (result) {
                    Thread.sleep(300); // 等待较长时间
                }
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "备用点击失败: " + e.getMessage());
            LogManager.log("✗ 备用点击失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 尝试使用Shell命令点击（需要root权限）
     */
    private boolean tryShellClick(int x, int y) {
        try {
            LogManager.log("尝试Shell命令点击: (" + x + "," + y + ")");
            
            // 方法1: 使用input tap命令
            String[] commands = {
                "su", "-c", "input tap " + x + " " + y
            };
            
            Process process = Runtime.getRuntime().exec(commands);
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                LogManager.log("✓ Shell tap命令执行成功");
                return true;
            } else {
                LogManager.log("✗ Shell tap命令失败，退出码: " + exitCode);
            }
            
            // 方法2: 使用sendevent命令（更底层）
            String[] touchCommands = {
                "su", "-c", String.format(
                    "sendevent /dev/input/event0 3 57 0 && " +
                    "sendevent /dev/input/event0 3 53 %d && " +
                    "sendevent /dev/input/event0 3 54 %d && " +
                    "sendevent /dev/input/event0 0 0 0 && " +
                    "sendevent /dev/input/event0 3 57 -1 && " +
                    "sendevent /dev/input/event0 0 0 0", x, y)
            };
            
            Process touchProcess = Runtime.getRuntime().exec(touchCommands);
            int touchExitCode = touchProcess.waitFor();
            
            if (touchExitCode == 0) {
                LogManager.log("✓ Shell sendevent命令执行成功");
                return true;
            } else {
                LogManager.log("✗ Shell sendevent命令失败，退出码: " + touchExitCode);
            }
            
        } catch (Exception e) {
            LogManager.log("✗ Shell点击异常: " + e.getMessage());
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
        // 停止周期性检查
        if (handler != null && periodicCheckRunnable != null) {
            handler.removeCallbacks(periodicCheckRunnable);
        }
        Log.i(TAG, "服务已销毁");
    }
    
    /**
     * 简单点击方法
     */
    private boolean performSimpleClick(int x, int y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                LogManager.log("执行简单点击: (" + x + "," + y + ")");
                
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(x, y);
                
                android.accessibilityservice.GestureDescription.StrokeDescription stroke = 
                    new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 150);
                
                android.accessibilityservice.GestureDescription gesture = 
                    new android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();
                
                final boolean[] completed = {false};
                final boolean[] success = {false};
                
                boolean result = dispatchGesture(gesture, new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(android.accessibilityservice.GestureDescription gestureDescription) {
                        super.onCompleted(gestureDescription);
                        completed[0] = true;
                        success[0] = true;
                        LogManager.log("✓ 简单点击完成");
                    }
                    
                    @Override
                    public void onCancelled(android.accessibilityservice.GestureDescription gestureDescription) {
                        super.onCancelled(gestureDescription);
                        completed[0] = true;
                        success[0] = false;
                        LogManager.log("✗ 简单点击取消");
                    }
                }, null);
                
                if (result) {
                    // 等待手势完成
                    int waitCount = 0;
                    while (!completed[0] && waitCount < 15) { // 最多等待1.5秒
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            break;
                        }
                        waitCount++;
                    }
                    return success[0];
                }
                
                LogManager.log("✗ 简单点击失败");
                return false;
            } catch (Exception e) {
                Log.e(TAG, "简单点击失败: " + e.getMessage());
                LogManager.log("✗ 简单点击异常: " + e.getMessage());
                return false;
            }
        }
        LogManager.log("✗ Android版本不支持手势点击");
        return false;
    }
    
    /**
     * 截图并识别绿色按钮位置 - 兼容性版本
     */
    private void takeScreenshotAndFindGreenButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用新的无障碍截图API
            takeScreenshotNew();
        } else {
            // 较旧版本使用传统方法
            LogManager.log("当前Android版本不支持无障碍截图，尝试其他方法");
            // 可以尝试通过节点分析来查找绿色按钮
            findGreenButtonByNodeAnalysis();
        }
    }
    
    /**
     * Android 11+ 的新截图方法
     */
    @SuppressWarnings("NewApi")
    private void takeScreenshotNew() {
        try {
            Log.i(TAG, "开始截图识别绿色按钮...");
            LogManager.log("开始截图识别绿色按钮...");
            
            // 使用无障碍服务的截图功能
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), 
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult screenshotResult) {
                        try {
                            Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.getHardwareBuffer(),
                                screenshotResult.getColorSpace()
                            );
                            
                            if (bitmap != null) {
                                Log.i(TAG, "截图成功: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                                LogManager.log("截图成功: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                                
                                // 分析图像找绿色按钮
                                android.graphics.Point greenButtonPos = findGreenButton(bitmap);
                                
                                if (greenButtonPos != null) {
                                    Log.i(TAG, "找到绿色按钮位置: (" + greenButtonPos.x + ", " + greenButtonPos.y + ")");
                                    LogManager.log("✓ 找到绿色按钮位置: (" + greenButtonPos.x + ", " + greenButtonPos.y + ")");
                                    performGlobalClick(greenButtonPos.x, greenButtonPos.y);
                                } else {
                                    Log.w(TAG, "未找到绿色按钮");
                                    LogManager.log("✗ 未找到绿色按钮，尝试节点分析");
                                    findGreenButtonByNodeAnalysis();
                                }
                                
                                bitmap.recycle();
                            } else {
                                LogManager.log("✗ 截图结果为空");
                                findGreenButtonByNodeAnalysis();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "处理截图失败: " + e.getMessage());
                            LogManager.log("✗ 处理截图失败: " + e.getMessage());
                            e.printStackTrace();
                            findGreenButtonByNodeAnalysis();
                        }
                    }
                    
                    @Override
                    public void onFailure(int errorCode) {
                        Log.e(TAG, "截图失败，错误码: " + errorCode);
                        LogManager.log("✗ 截图失败，错误码: " + errorCode + "，尝试节点分析");
                        findGreenButtonByNodeAnalysis();
                    }
                });
                
        } catch (Exception e) {
            Log.e(TAG, "截图异常: " + e.getMessage());
            LogManager.log("✗ 截图异常: " + e.getMessage());
            e.printStackTrace();
            findGreenButtonByNodeAnalysis();
        }
    }
    
    /**
     * 通过节点分析查找绿色按钮（备用方案）
     */
    private void findGreenButtonByNodeAnalysis() {
        LogManager.log("开始节点分析查找绿色按钮");
        
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            LogManager.log("✗ 无法获取根节点");
            return;
        }
        
        // 查找可能的绿色按钮关键词
        String[] greenButtonKeywords = {
            "发送", "确定", "完成", "提交", "确认", "同意", 
            "Send", "OK", "Done", "Submit", "Confirm", "Agree"
        };
        
        for (String keyword : greenButtonKeywords) {
            List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(keyword);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    LogManager.log("找到可能的按钮: " + keyword);
                    if (tryClickNode(node, "绿色按钮(" + keyword + ")")) {
                        LogManager.log("✓ 成功点击按钮: " + keyword);
                        rootNode.recycle();
                        return;
                    }
                }
            }
        }
        
        LogManager.log("✗ 未找到匹配的绿色按钮");
        rootNode.recycle();
    }
    
    /**
     * 分析图像找绿色按钮 - 优化版本
     */
    private android.graphics.Point findGreenButton(Bitmap bitmap) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            
            Log.i(TAG, "开始分析图像，尺寸: " + width + "x" + height);
            LogManager.log("开始分析图像，尺寸: " + width + "x" + height);
            
            // 多区域扫描策略
            android.graphics.Point result = null;
            
            // 1. 优先扫描常见按钮位置：屏幕底部右下角
            result = scanRegionForGreen(bitmap, (int)(width * 0.7), width, (int)(height * 0.8), height, 8, "右下角");
            if (result != null) return result;
            
            // 2. 扫描屏幕底部中间区域
            result = scanRegionForGreen(bitmap, (int)(width * 0.3), (int)(width * 0.7), (int)(height * 0.8), height, 8, "底部中间");
            if (result != null) return result;
            
            // 3. 扫描屏幕下半部分
            result = scanRegionForGreen(bitmap, 0, width, height / 2, (int)(height * 0.9), 12, "下半部分");
            if (result != null) return result;
            
            // 4. 扫描整个屏幕（最后手段）
            result = scanRegionForGreen(bitmap, 0, width, 0, height, 15, "全屏");
            if (result != null) return result;
            
            LogManager.log("✗ 未找到绿色按钮");
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "分析图像异常: " + e.getMessage());
            LogManager.log("✗ 分析图像异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 在指定区域扫描绿色按钮
     */
    private android.graphics.Point scanRegionForGreen(Bitmap bitmap, int startX, int endX, int startY, int endY, int step, String regionName) {
        LogManager.log("扫描区域: " + regionName + " (" + startX + "," + startY + ")-(" + endX + "," + endY + ")");
        
        int maxGreenScore = 0;
        android.graphics.Point bestPosition = null;
        
        for (int y = startY; y < endY; y += step) {
            for (int x = startX; x < endX; x += step) {
                try {
                    int pixel = bitmap.getPixel(x, y);
                    int greenScore = calculateGreenScore(pixel);
                    
                    if (greenScore > maxGreenScore && greenScore > 80) { // 提高阈值
                        maxGreenScore = greenScore;
                        bestPosition = new android.graphics.Point(x, y);
                    }
                } catch (Exception e) {
                    // 忽略越界错误
                    continue;
                }
            }
        }
        
        if (bestPosition != null) {
            LogManager.log("✓ 在" + regionName + "找到绿色区域，得分: " + maxGreenScore + ", 位置: (" + bestPosition.x + ", " + bestPosition.y + ")");
            
            // 在绿色区域周围寻找更精确的中心点
            android.graphics.Point center = findGreenCenter(bitmap, bestPosition.x, bestPosition.y);
            return center != null ? center : bestPosition;
        }
        
        return null;
    }
    
    /**
     * 计算绿色得分（更精确的绿色检测算法）
     */
    private int calculateGreenScore(int pixel) {
        int red = Color.red(pixel);
        int green = Color.green(pixel);
        int blue = Color.blue(pixel);
        
        // 微信绿色按钮的特征值
        // 一般是 RGB(7, 193, 96) 或类似的绿色
        
        // 基础绿色检测：绿色明显高于红色和蓝色
        if (green < 120 || green <= red + 40 || green <= blue + 40) {
            return 0;
        }
        
        // 计算与微信绿色的相似度
        int targetR = 7, targetG = 193, targetB = 96;
        
        // 欧几里得距离（越小越相似）
        int distance = (int) Math.sqrt(
            Math.pow(red - targetR, 2) + 
            Math.pow(green - targetG, 2) + 
            Math.pow(blue - targetB, 2)
        );
        
        // 距离越小，得分越高（最大200分）
        int similarityScore = Math.max(0, 200 - distance);
        
        // 绿色强度得分
        int intensityScore = green - Math.max(red, blue);
        
        // 综合得分
        int totalScore = similarityScore + intensityScore;
        
        return totalScore;
    }
    
    /**
     * 在绿色区域周围找到中心点 - 优化版本
     */
    private android.graphics.Point findGreenCenter(Bitmap bitmap, int startX, int startY) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            
            // 在起始点周围 80x80 的区域内寻找绿色中心
            int searchRadius = 40;
            int minX = Math.max(0, startX - searchRadius);
            int maxX = Math.min(width - 1, startX + searchRadius);
            int minY = Math.max(0, startY - searchRadius);
            int maxY = Math.min(height - 1, startY + searchRadius);
            
            int totalX = 0;
            int totalY = 0;
            int totalScore = 0;
            int greenCount = 0;
            
            LogManager.log("搜索绿色中心，区域: (" + minX + "," + minY + ")-(" + maxX + "," + maxY + ")");
            
            for (int y = minY; y <= maxY; y += 2) { // 步长为2，提高速度
                for (int x = minX; x <= maxX; x += 2) {
                    try {
                        int pixel = bitmap.getPixel(x, y);
                        int greenScore = calculateGreenScore(pixel);
                        
                        if (greenScore > 60) { // 使用新的绿色检测算法
                            totalX += x * greenScore; // 权重计算
                            totalY += y * greenScore;
                            totalScore += greenScore;
                            greenCount++;
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
            }
            
            if (greenCount > 0 && totalScore > 0) {
                int centerX = totalX / totalScore; // 基于权重的中心点
                int centerY = totalY / totalScore;
                
                LogManager.log("✓ 绿色中心点: (" + centerX + ", " + centerY + "), 绿色像素数: " + greenCount + ", 总得分: " + totalScore);
                return new android.graphics.Point(centerX, centerY);
            }
            
            LogManager.log("✗ 未找到足够的绿色像素");
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "查找绿色中心异常: " + e.getMessage());
            LogManager.log("✗ 查找绿色中心异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 系统诊断 - 检查所有必要的权限和配置
     */
    private void performSystemDiagnosis() {
        LogManager.log("=== 开始系统诊断 ===");
        
        // 1. 检查Android版本
        int sdkVersion = Build.VERSION.SDK_INT;
        LogManager.log("Android版本: " + Build.VERSION.RELEASE + " (SDK " + sdkVersion + ")");
        
        // 2. 检查无障碍服务权限
        boolean accessibilityEnabled = isServiceEnabled(this);
        LogManager.log("无障碍服务状态: " + (accessibilityEnabled ? "已启用" : "未启用"));
        
        // 3. 检查手势支持
        boolean gestureSupported = sdkVersion >= Build.VERSION_CODES.N;
        LogManager.log("手势支持: " + (gestureSupported ? "支持" : "不支持 (需要Android 7.0+)"));
        
        // 4. 检查截图支持
        boolean screenshotSupported = sdkVersion >= Build.VERSION_CODES.R;
        LogManager.log("截图支持: " + (screenshotSupported ? "支持" : "不支持 (需要Android 11+)"));
        
        // 5. 检查服务信息
        android.accessibilityservice.AccessibilityServiceInfo serviceInfo = getServiceInfo();
        if (serviceInfo != null) {
            LogManager.log("服务能力:");
            LogManager.log("- 可执行手势: " + serviceInfo.canPerformGestures());
            LogManager.log("- 可获取窗口内容: " + serviceInfo.canRetrieveWindowContent());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                LogManager.log("- 可截图: " + serviceInfo.canTakeScreenshot());
            }
        } else {
            LogManager.log("✗ 无法获取服务信息");
        }
        
        // 6. 检查设置权限
        try {
            boolean canWriteSettings = Settings.System.canWrite(this);
            LogManager.log("系统设置权限: " + (canWriteSettings ? "已授权" : "未授权"));
        } catch (Exception e) {
            LogManager.log("系统设置权限检查失败: " + e.getMessage());
        }
        
        LogManager.log("=== 系统诊断完成 ===");
    }
    
    /**
     * 测试截图功能
     */
    private void testScreenshotFunction() {
        LogManager.log("=== 测试截图功能 ===");
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            LogManager.log("✗ 当前Android版本不支持无障碍截图");
            return;
        }
        
        try {
            LogManager.log("开始测试截图...");
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), 
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult screenshotResult) {
                        try {
                            LogManager.log("✓ 截图API调用成功");
                            
                            if (screenshotResult.getHardwareBuffer() != null) {
                                LogManager.log("✓ 获得HardwareBuffer");
                                
                                Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                                    screenshotResult.getHardwareBuffer(),
                                    screenshotResult.getColorSpace()
                                );
                                
                                if (bitmap != null) {
                                    LogManager.log("✓ 截图成功: " + bitmap.getWidth() + "x" + bitmap.getHeight());
                                    bitmap.recycle();
                                } else {
                                    LogManager.log("✗ Bitmap创建失败");
                                }
                            } else {
                                LogManager.log("✗ HardwareBuffer为空");
                            }
                        } catch (Exception e) {
                            LogManager.log("✗ 截图处理异常: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                    
                    @Override
                    public void onFailure(int errorCode) {
                        LogManager.log("✗ 截图失败，错误码: " + errorCode);
                        
                        // 解释错误码
                        String errorMsg = "未知错误";
                        switch (errorCode) {
                            case ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR:
                                errorMsg = "内部错误";
                                break;
                            case ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS:
                                errorMsg = "无无障碍权限";
                                break;
                            case ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT:
                                errorMsg = "截图间隔时间太短";
                                break;
                        }
                        LogManager.log("错误详情: " + errorMsg);
                    }
                });
        } catch (Exception e) {
            LogManager.log("✗ 截图测试异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 测试点击功能
     */
    private void testClickFunction() {
        LogManager.log("=== 测试点击功能 ===");
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            LogManager.log("✗ 当前Android版本不支持手势点击");
            return;
        }
        
        // 测试点击屏幕中心
        int testX = screenWidth / 2;
        int testY = screenHeight / 2;
        
        LogManager.log("测试点击屏幕中心: (" + testX + ", " + testY + ")");
        
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(testX, testY);
            
            android.accessibilityservice.GestureDescription.StrokeDescription stroke = 
                new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100);
            
            android.accessibilityservice.GestureDescription gesture = 
                new android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            
            boolean result = dispatchGesture(gesture, new android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(android.accessibilityservice.GestureDescription gestureDescription) {
                    super.onCompleted(gestureDescription);
                    LogManager.log("✓ 测试点击完成");
                }
                
                @Override
                public void onCancelled(android.accessibilityservice.GestureDescription gestureDescription) {
                    super.onCancelled(gestureDescription);
                    LogManager.log("✗ 测试点击被取消");
                }
            }, null);
            
            LogManager.log("手势分发结果: " + (result ? "成功" : "失败"));
            
        } catch (Exception e) {
            LogManager.log("✗ 点击测试异常: " + e.getMessage());
            e.printStackTrace();
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
