package com.hubra.core

object InitialData {
    fun create(): AppState {
        val times=listOf("08:00" to "08:50","09:00" to "09:50","10:10" to "11:00","11:10" to "12:00","14:00" to "14:50","15:00" to "15:50","16:10" to "17:00","17:10" to "18:00","18:30" to "19:20","19:30" to "20:20","20:30" to "21:20")
        val courses=listOf(
            Course("politics","中国式现代化的理论与实践","10284A012","徐坤、刘晓民、吴秋怡","#D993AE"),
            Course("ai","生成式人工智能高级应用","120502B74","李白杨","#AF9AE5"),
            Course("matrix","矩阵论基础","0810Z1B001","陈建琪","#64CCBC"),
            Course("control","现代控制理论","0810Z1B002","张建强","#7AA5EB"),
            Course("functional","工程泛函","0810Z1C002","付瑞","#E7B46D"),
            Course("signal","信号处理","0810Z1C003","杨博渊","#71A7A1"),
            Course("modeling","控制系统建模",teacher="朱波",color="#E98775",pending=true,sourceText="图2：仅确认第7周周三3–4节；完整周次待补全")
        )
        fun rule(id:String,c:String,d:Int,s:Int,e:Int,w:List<Int>,r:String)=CourseRule(id,c,d,s,e,w,r)
        val sparse=listOf(3)+(5..10).toList()
        return AppState(schemaVersion=3,semesters=listOf(Semester("default","2026 秋季","2026-08-24",20)),accounts=listOf(Account("campus","校园卡"),Account("transport","交通卡"),Account("bank","银行卡"),Account("wechat","微信零钱")),
            settings=Settings(periods=times.mapIndexed{i,p->Period(i+1,p.first,p.second)}), courses=courses,
            courseRules=listOf(rule("politics-thu","politics",4,7,8,(3..18).toList(),"苏教B209"),rule("ai-wed","ai",3,5,6,(3..18).toList(),"南雍-西426"),
                rule("matrix-wed","matrix",3,7,8,(3..10).toList(),"南雍-西353"),rule("matrix-fri","matrix",5,9,10,sparse,"南雍-西353"),rule("matrix-extra","matrix",2,9,10,listOf(5),"南雍-西353"),
                rule("control-extra","control",6,5,7,listOf(3),"南雍-西311"),rule("control-fri","control",5,5,7,(4..18).toList(),"南雍-西353"),
                rule("functional-tue","functional",2,5,6,(3..10).toList(),"南雍-西447"),rule("functional-thu","functional",4,3,4,sparse,"南雍-西447"),rule("signal-mon","signal",1,5,6,(3..18).toList(),"南雍-西353"),
                rule("modeling-candidate","modeling",3,3,4,listOf(7),"南雍-西353")),profiles=listOf(ApiProfile("monk","Monk","https://monk.party/v1")))
    }
}
