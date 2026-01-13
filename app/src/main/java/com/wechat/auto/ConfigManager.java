package com.wechat.auto;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    
    private static final String PREF_NAME = "wechat_auto_config";
    private static final String KEY_AUTO_REPLY = "auto_reply_enabled";
    private static final String KEY_AUTO_ANSWER = "auto_answer_enabled";
    private static final String KEY_COOLDOWN = "cooldown_seconds";
    private static final String KEY_KEYWORDS = "keywords";
    private static final String KEY_MATCH_MODE = "match_mode";
    
    private SharedPreferences prefs;
    private Gson gson;
    
    public ConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }
    
    // 自动回复开关
    public boolean isAutoReplyEnabled() {
        return prefs.getBoolean(KEY_AUTO_REPLY, true);
    }
    
    public void setAutoReplyEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_REPLY, enabled).apply();
    }
    
    // 自动接听开关
    public boolean isAutoAnswerEnabled() {
        return prefs.getBoolean(KEY_AUTO_ANSWER, true);
    }
    
    public void setAutoAnswerEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_ANSWER, enabled).apply();
    }
    
    // 冷却时间
    public int getCooldownSeconds() {
        return prefs.getInt(KEY_COOLDOWN, 5);
    }
    
    public void setCooldownSeconds(int seconds) {
        prefs.edit().putInt(KEY_COOLDOWN, seconds).apply();
    }
    
    // 匹配模式
    public String getMatchMode() {
        return prefs.getString(KEY_MATCH_MODE, "fuzzy");
    }
    
    public void setMatchMode(String mode) {
        prefs.edit().putString(KEY_MATCH_MODE, mode).apply();
    }
    
    // 关键词管理
    public List<KeywordItem> getKeywords() {
        String json = prefs.getString(KEY_KEYWORDS, "[]");
        Type type = new TypeToken<List<KeywordItem>>(){}.getType();
        List<KeywordItem> keywords = gson.fromJson(json, type);
        return keywords != null ? keywords : new ArrayList<>();
    }
    
    public void addKeyword(String keyword, String reply) {
        List<KeywordItem> keywords = getKeywords();
        keywords.add(new KeywordItem(keyword, reply));
        saveKeywords(keywords);
    }
    
    public void removeKeyword(int position) {
        List<KeywordItem> keywords = getKeywords();
        if (position >= 0 && position < keywords.size()) {
            keywords.remove(position);
            saveKeywords(keywords);
        }
    }
    
    public void updateKeyword(int position, String keyword, String reply) {
        List<KeywordItem> keywords = getKeywords();
        if (position >= 0 && position < keywords.size()) {
            keywords.set(position, new KeywordItem(keyword, reply));
            saveKeywords(keywords);
        }
    }
    
    private void saveKeywords(List<KeywordItem> keywords) {
        String json = gson.toJson(keywords);
        prefs.edit().putString(KEY_KEYWORDS, json).apply();
    }
    
    /**
     * 检查消息是否匹配关键词
     */
    public String checkKeyword(String message) {
        if (message == null || message.isEmpty()) return null;
        
        List<KeywordItem> keywords = getKeywords();
        String matchMode = getMatchMode();
        
        for (KeywordItem item : keywords) {
            if ("exact".equals(matchMode)) {
                // 精确匹配
                if (message.trim().equals(item.keyword)) {
                    return item.reply;
                }
            } else {
                // 模糊匹配
                if (message.contains(item.keyword)) {
                    return item.reply;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 关键词数据类
     */
    public static class KeywordItem {
        public String keyword;
        public String reply;
        
        public KeywordItem(String keyword, String reply) {
            this.keyword = keyword;
            this.reply = reply;
        }
    }
}
