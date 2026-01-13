package com.wechat.auto;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogManager {
    private static final int MAX_LOGS = 200;
    private static final List<String> logs = new ArrayList<>();
    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    
    public static void log(String message) {
        String timestamp = dateFormat.format(new Date());
        String logEntry = timestamp + " - " + message;
        
        synchronized (logs) {
            logs.add(logEntry);
            if (logs.size() > MAX_LOGS) {
                logs.remove(0);
            }
        }
    }
    
    public static List<String> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }
    
    public static void clear() {
        synchronized (logs) {
            logs.clear();
        }
    }
    
    public static String getAllLogsAsString() {
        synchronized (logs) {
            StringBuilder sb = new StringBuilder();
            for (String log : logs) {
                sb.append(log).append("\n");
            }
            return sb.toString();
        }
    }
}
