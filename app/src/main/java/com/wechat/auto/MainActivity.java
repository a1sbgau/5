package com.wechat.auto;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    
    private SwitchCompat switchAutoReply;
    private SwitchCompat switchAutoAnswer;
    private EditText editCooldown;
    private TextView tvServiceStatus;
    private Button btnOpenSettings;
    private FloatingActionButton fabAddKeyword;
    private RecyclerView recyclerKeywords;
    
    private KeywordAdapter keywordAdapter;
    private ConfigManager configManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            
            configManager = new ConfigManager(this);
            
            initViews();
            loadConfig();
            updateServiceStatus();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void initViews() {
        try {
            switchAutoReply = findViewById(R.id.switch_auto_reply);
            switchAutoAnswer = findViewById(R.id.switch_auto_answer);
            editCooldown = findViewById(R.id.edit_cooldown);
            tvServiceStatus = findViewById(R.id.tv_service_status);
            btnOpenSettings = findViewById(R.id.btn_open_settings);
            fabAddKeyword = findViewById(R.id.fab_add_keyword);
            recyclerKeywords = findViewById(R.id.recycler_keywords);
            
            if (switchAutoReply == null || switchAutoAnswer == null || 
                editCooldown == null || tvServiceStatus == null || 
                btnOpenSettings == null || fabAddKeyword == null || 
                recyclerKeywords == null) {
                Toast.makeText(this, "界面初始化失败", Toast.LENGTH_LONG).show();
                return;
            }
            
            // 设置开关监听
            switchAutoReply.setOnCheckedChangeListener((buttonView, isChecked) -> {
                configManager.setAutoReplyEnabled(isChecked);
            });
            
            switchAutoAnswer.setOnCheckedChangeListener((buttonView, isChecked) -> {
                configManager.setAutoAnswerEnabled(isChecked);
            });
            
            // 打开无障碍设置
            btnOpenSettings.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show();
                }
            });
            
            // 添加关键词
            fabAddKeyword.setOnClickListener(v -> showAddKeywordDialog());
            
            // 设置关键词列表
            recyclerKeywords.setLayoutManager(new LinearLayoutManager(this));
            keywordAdapter = new KeywordAdapter(configManager.getKeywords(), new KeywordAdapter.OnKeywordActionListener() {
                @Override
                public void onDelete(int position) {
                    configManager.removeKeyword(position);
                    keywordAdapter.notifyItemRemoved(position);
                    Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                }
                
                @Override
                public void onEdit(int position, String keyword, String reply) {
                    showEditKeywordDialog(position, keyword, reply);
                }
            });
            recyclerKeywords.setAdapter(keywordAdapter);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "界面初始化异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    private void loadConfig() {
        switchAutoReply.setChecked(configManager.isAutoReplyEnabled());
        switchAutoAnswer.setChecked(configManager.isAutoAnswerEnabled());
        editCooldown.setText(String.valueOf(configManager.getCooldownSeconds()));
        
        keywordAdapter.updateData(configManager.getKeywords());
    }
    
    private void updateServiceStatus() {
        boolean isEnabled = WeChatAccessibilityService.isServiceEnabled(this);
        if (isEnabled) {
            tvServiceStatus.setText("服务状态: ✓ 运行中");
            tvServiceStatus.setTextColor(0xFF4CAF50); // 绿色
        } else {
            tvServiceStatus.setText("服务状态: ✗ 未启动");
            tvServiceStatus.setTextColor(0xFFF44336); // 红色
        }
    }
    
    private void showAddKeywordDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_keyword, null);
        EditText editKeyword = dialogView.findViewById(R.id.edit_keyword);
        EditText editReply = dialogView.findViewById(R.id.edit_reply);
        
        new AlertDialog.Builder(this)
            .setTitle("添加关键词")
            .setView(dialogView)
            .setPositiveButton("添加", (dialog, which) -> {
                String keyword = editKeyword.getText().toString().trim();
                String reply = editReply.getText().toString().trim();
                
                if (keyword.isEmpty() || reply.isEmpty()) {
                    Toast.makeText(this, "关键词和回复不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                configManager.addKeyword(keyword, reply);
                keywordAdapter.updateData(configManager.getKeywords());
                Toast.makeText(this, "添加成功", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void showEditKeywordDialog(int position, String keyword, String reply) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_keyword, null);
        EditText editKeyword = dialogView.findViewById(R.id.edit_keyword);
        EditText editReply = dialogView.findViewById(R.id.edit_reply);
        
        editKeyword.setText(keyword);
        editReply.setText(reply);
        
        new AlertDialog.Builder(this)
            .setTitle("编辑关键词")
            .setView(dialogView)
            .setPositiveButton("保存", (dialog, which) -> {
                String newKeyword = editKeyword.getText().toString().trim();
                String newReply = editReply.getText().toString().trim();
                
                if (newKeyword.isEmpty() || newReply.isEmpty()) {
                    Toast.makeText(this, "关键词和回复不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                configManager.updateKeyword(position, newKeyword, newReply);
                keywordAdapter.updateData(configManager.getKeywords());
                Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        
        // 保存冷却时间
        String cooldownStr = editCooldown.getText().toString();
        if (!cooldownStr.isEmpty()) {
            try {
                int cooldown = Integer.parseInt(cooldownStr);
                configManager.setCooldownSeconds(cooldown);
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
    }
}
