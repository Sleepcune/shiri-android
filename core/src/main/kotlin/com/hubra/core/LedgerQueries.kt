package com.hubra.core

import java.time.LocalDate

data class LedgerFilter(val accountId:String?=null,val startDate:LocalDate,val endDate:LocalDate,val category:String?=null,val search:String="")
data class CategoryTotal(val category:String,val expenseCents:Long,val refundCents:Long,val netCents:Long,val share:Float)
data class LedgerSummary(val records:List<Transaction>,val expenseCents:Long,val refundCents:Long,val netCents:Long,val categories:List<CategoryTotal>)

object LedgerQueries {
    fun category(state:AppState,record:Transaction):String {
        val original=if(record.type=="refund")state.transactions.firstOrNull{it.id==record.relatedId && it.type=="expense"} else null
        return (original?.category?:record.category).ifBlank{"其他"}
    }
    fun summary(state:AppState,filter:LedgerFilter):LedgerSummary {
        require(filter.startDate<=filter.endDate){"起始日期不能晚于结束日期"}
        val records=state.transactions.filter { record ->
            val day=runCatching{LocalDate.parse(record.date)}.getOrNull()
            !record.deleted && day!=null && day>=filter.startDate && day<=filter.endDate &&
                (filter.accountId==null || record.accountId==filter.accountId || record.type=="transfer" && record.toAccountId==filter.accountId) &&
                (filter.category==null || record.type in setOf("expense","refund") && category(state,record)==filter.category) &&
                (filter.search.isBlank() || record.note.contains(filter.search.trim(),ignoreCase=true) || category(state,record).contains(filter.search.trim(),ignoreCase=true))
        }.sortedWith(compareByDescending<Transaction>{it.date}.thenByDescending{it.createdAt}.thenBy{it.id})
        fun sum(items:List<Transaction>,type:String)=items.filter{it.type==type}.fold(0L){value,item->Math.addExact(value,item.amountCents)}
        val expense=sum(records,"expense");val refunds=sum(records,"refund")
        val categories=records.filter{it.type=="expense" || it.type=="refund"}.groupBy{category(state,it)}.map{(name,items)->
            val spent=sum(items,"expense");val refunded=sum(items,"refund")
            CategoryTotal(name,spent,refunded,Math.subtractExact(spent,refunded),if(expense==0L)0f else (spent.toDouble()/expense).toFloat())
        }.sortedWith(compareByDescending<CategoryTotal>{it.expenseCents}.thenBy{it.category})
        return LedgerSummary(records,expense,refunds,Math.subtractExact(expense,refunds),categories)
    }
    fun query(state:AppState,filter:LedgerFilter)=summary(state,filter)
}
