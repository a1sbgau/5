package com.wechat.auto;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class KeywordAdapter extends RecyclerView.Adapter<KeywordAdapter.ViewHolder> {
    
    private List<ConfigManager.KeywordItem> keywords;
    private OnKeywordActionListener listener;
    
    public interface OnKeywordActionListener {
        void onDelete(int position);
        void onEdit(int position, String keyword, String reply);
    }
    
    public KeywordAdapter(List<ConfigManager.KeywordItem> keywords, OnKeywordActionListener listener) {
        this.keywords = keywords != null ? keywords : new ArrayList<>();
        this.listener = listener;
    }
    
    public void updateData(List<ConfigManager.KeywordItem> newKeywords) {
        this.keywords = newKeywords != null ? newKeywords : new ArrayList<>();
        notifyDataSetChanged();
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_keyword, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ConfigManager.KeywordItem item = keywords.get(position);
        holder.tvKeyword.setText(item.keyword);
        holder.tvReply.setText(item.reply);
        
        holder.btnEdit.setOnClickListener(v -> {
            if (listener != null) {
                listener.onEdit(position, item.keyword, item.reply);
            }
        });
        
        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDelete(position);
            }
        });
    }
    
    @Override
    public int getItemCount() {
        return keywords.size();
    }
    
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvKeyword;
        TextView tvReply;
        ImageButton btnEdit;
        ImageButton btnDelete;
        
        ViewHolder(View itemView) {
            super(itemView);
            tvKeyword = itemView.findViewById(R.id.tv_keyword);
            tvReply = itemView.findViewById(R.id.tv_reply);
            btnEdit = itemView.findViewById(R.id.btn_edit);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
