package com.wechat.auto;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class NotificationService extends NotificationListenerService {
    
    private static final String TAG = "NotificationService";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName() == null) return;
        
        if (WECHAT_PACKAGE.equals(sbn.getPackageName())) {
            Log.d(TAG, "收到微信通知");
            // 通知已由 AccessibilityService 处理
        }
    }
    
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 通知被移除
    }
}
