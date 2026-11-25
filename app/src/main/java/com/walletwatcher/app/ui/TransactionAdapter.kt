package com.walletwatcher.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.walletwatcher.app.R
import com.walletwatcher.app.core.AuditLogEntry
import com.walletwatcher.app.core.Transaction
import com.walletwatcher.app.core.TxDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ListRow {
    data class TxRow(val tx: Transaction, val isIgnoredTab: Boolean) : ListRow()
    data class LogRow(val log: AuditLogEntry) : ListRow()
}

class TransactionAdapter(
    private val onRescueClick: (Transaction) -> Unit,
    private val onDeepLinkClick: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.RowViewHolder>() {

    private var items: List<ListRow> = emptyList()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun submitList(newItems: List<ListRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transaction, parent, false)
        return RowViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        when (val row = items[position]) {
            is ListRow.TxRow -> bindTx(holder, row.tx, row.isIgnoredTab)
            is ListRow.LogRow -> bindLog(holder, row.log)
        }
    }

    private fun bindTx(holder: RowViewHolder, tx: Transaction, isIgnoredTab: Boolean) {
        val ctx = holder.itemView.context
        holder.tvTitle.text = tx.merchant

        val sign = when (tx.direction) {
            TxDirection.EXPENSE -> "-"
            TxDirection.INCOME -> "+"
            TxDirection.REFUND -> "退 "
            else -> ""
        }
        holder.tvAmount.text = "${sign}¥${String.format(Locale.CHINA, "%.2f", tx.amount)}"
        val colorRes = when (tx.direction) {
            TxDirection.EXPENSE -> R.color.expense_red
            TxDirection.INCOME -> R.color.income_green
            TxDirection.REFUND -> R.color.refund_blue
            else -> R.color.text_muted
        }
        holder.tvAmount.setTextColor(ContextCompat.getColor(ctx, colorRes))

        val mergedTag = if (tx.mergedNotifications > 1) " · 合并${tx.mergedNotifications}条" else ""
        val rescueTag = if (tx.rescuedByUser) " · 已捞回" else ""
        holder.tvMeta.text = "${tx.category} · ${tx.paymentMethod} · ${timeFmt.format(Date(tx.createdAtMs))}$mergedTag$rescueTag"

        val reasonPrefix = if (isIgnoredTab) "拦截原因：" else "判定说明："
        val reasonBody = if (isIgnoredTab) tx.ignoreReason.ifBlank { tx.aiReasoning } else "${tx.itemSummary}（${tx.aiReasoning}）"
        holder.tvReason.visibility = View.VISIBLE
        holder.tvReason.text = "$reasonPrefix$reasonBody"

        holder.actionRow.visibility = View.VISIBLE
        if (isIgnoredTab) {
            holder.btnAction.text = "误判？捞回主账本"
            holder.btnAction.setOnClickListener { onRescueClick(tx) }
        } else {
            holder.btnAction.text = tx.deepLinkLabel.ifBlank { "查看原账单" }
            holder.btnAction.setOnClickListener { onDeepLinkClick(tx) }
        }
    }

    private fun bindLog(holder: RowViewHolder, log: AuditLogEntry) {
        val ctx = holder.itemView.context
        holder.tvTitle.text = "[${log.stage}]"
        holder.tvAmount.text = timeFmt.format(Date(log.createdAtMs))
        holder.tvAmount.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
        holder.tvMeta.text = log.message

        if (log.sanitizedSample.isNotBlank()) {
            holder.tvReason.visibility = View.VISIBLE
            holder.tvReason.text = "脱敏原文：${log.sanitizedSample}"
        } else {
            holder.tvReason.visibility = View.GONE
        }
        holder.actionRow.visibility = View.GONE
    }

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        val tvReason: TextView = view.findViewById(R.id.tvReason)
        val actionRow: LinearLayout = view.findViewById(R.id.actionRow)
        val btnAction: Button = view.findViewById(R.id.btnAction)
    }
}
