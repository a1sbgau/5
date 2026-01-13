package com.wechat.auto;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class LogActivity extends AppCompatActivity {
    
    private TextView tvLogs;
    private ScrollView scrollView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable updateRunnable;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 创建简单的布局
        ScrollView scrollView = new ScrollView(this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);
        
        // 标题
        TextView title = new TextView(this);
        title.setText("运行日志");
        title.setTextSize(20);
        title.setTextColor(0xFF000000);
        title.setPadding(0, 0, 0, 16);
        layout.addView(title);
        
        // 清除按钮
        Button btnClear = new Button(this);
        btnClear.setText("清除日志");
        btnClear.setOnClickListener(v -> {
            LogManager.clear();
            updateLogs();
        });
        layout.addView(btnClear);
        
        // 日志文本
        tvLogs = new TextView(this);
        tvLogs.setTextSize(12);
        tvLogs.setTextColor(0xFF333333);
        tvLogs.setPadding(8, 8, 8, 8);
        tvLogs.setBackgroundColor(0xFFF5F5F5);
        layout.addView(tvLogs);
        
        scrollView.addView(layout);
        setContentView(scrollView);
        
        // 定时更新日志
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateLogs();
                handler.postDelayed(this, 1000); // 每秒更新
            }
        };
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        handler.post(updateRunnable);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(updateRunnable);
    }
    
    private void updateLogs() {
        String logs = LogManager.getAllLogsAsString();
        if (logs.isEmpty()) {
            tvLogs.setText("暂无日志\n\n提示：\n1. 确保无障碍服务已开启\n2. 让朋友给你打视频电话\n3. 观察这里的日志输出");
        } else {
            tvLogs.setText(logs);
        }
    }
}
