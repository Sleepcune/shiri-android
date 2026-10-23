package com.hubra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hubra.core.*
import com.hubra.app.media.AttachmentManager
import coil.compose.AsyncImage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TimetableAppearanceSheet(s:AppState,execute:Execute,run:Run,pickBackground:()->Unit={},onClose:()->Unit) {
    val semesterId=s.settings.activeSemesterId
    val context=LocalContext.current
    val original=s.settings.timetableAppearances[semesterId]?:TimetableAppearance()
    var font by rememberSaveable(semesterId){mutableFloatStateOf(original.fontSizeSp.toFloat())}
    var height by rememberSaveable(semesterId){mutableFloatStateOf(original.rowHeightDp.toFloat())}
    var corner by rememberSaveable(semesterId){mutableFloatStateOf(original.cornerRadiusDp.toFloat())}
    var opacity by rememberSaveable(semesterId){mutableFloatStateOf(original.opacity.toFloat())}
    var weekends by rememberSaveable(semesterId){mutableStateOf(original.showWeekends)}
    var teacher by rememberSaveable(semesterId){mutableStateOf(original.showTeacher)}
    var times by rememberSaveable(semesterId){mutableStateOf(original.showTimes)}
    var color by rememberSaveable(semesterId){mutableStateOf(original.backgroundColor)}
    var background by rememberSaveable(semesterId){mutableStateOf(original.backgroundAttachmentId)}
    LaunchedEffect(original.backgroundAttachmentId){background=original.backgroundAttachmentId}
    val tint=runCatching{Color(android.graphics.Color.parseColor(color))}.getOrDefault(Color(0xFFF3F4FC))
    val validColor=Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(color)
    fun payload():JsonObject {
        val values=HubraJson.json.encodeToJsonElement(original).jsonObject.toMutableMap()
        values["fontSizeSp"]=JsonPrimitive(font.roundToInt());values["rowHeightDp"]=JsonPrimitive(height.roundToInt());values["cornerRadiusDp"]=JsonPrimitive(corner.roundToInt());values["opacity"]=JsonPrimitive(opacity)
        values["showWeekends"]=JsonPrimitive(weekends);values["showTeacher"]=JsonPrimitive(teacher);values["showTimes"]=JsonPrimitive(times);values["backgroundColor"]=JsonPrimitive(color);values["backgroundAttachmentId"]=background?.let(::JsonPrimitive)?:JsonNull
        val current=HubraJson.json.encodeToJsonElement(s.settings.timetableAppearances).jsonObject.toMutableMap();current[semesterId]=JsonObject(values)
        return args("timetableAppearances" to JsonObject(current))
    }
    ModalBottomSheet(onDismissRequest=onClose){Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("${activeSemesterName(s)} · 显示设置",style=MaterialTheme.typography.titleLarge)
        Text("预览",style=MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth().height((height*2+16).dp).background(tint,RoundedCornerShape(12.dp))){
        background?.let{id->s.attachments.find{it.id==id && it.deletedAt==null && !it.deletionPending}?.let{photo->AsyncImage(model=AttachmentManager(context).file(photo),contentDescription="背景预览",contentScale=ContentScale.Crop,alpha=.35f,modifier=Modifier.fillMaxSize())}}
        Row(Modifier.fillMaxSize().padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            if(times)Text("3\n10:10\n11:00",fontSize=10.sp)
            Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF64CCBC).copy(alpha=opacity),RoundedCornerShape(corner.dp)).padding(8.dp)){Text("矩阵论",fontSize=font.sp,lineHeight=(font+2).sp,color=Color(0xFF172035));Text("西353",fontSize=(font-2).coerceAtLeast(9f).sp,lineHeight=font.sp,color=Color(0xFF172035));if(teacher)Text("陈建琪",fontSize=10.sp,lineHeight=12.sp,color=Color(0xFF172035))}
            Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFAF9AE5).copy(alpha=opacity),RoundedCornerShape(corner.dp)).padding(8.dp)){Text("生成式AI",fontSize=font.sp,lineHeight=(font+2).sp,color=Color(0xFF172035));Text("西426",fontSize=10.sp,lineHeight=12.sp,color=Color(0xFF172035))}
        }}
        Text("文字 ${font.roundToInt()} sp");Slider(font,{font=it},valueRange=9f..18f,steps=8)
        Text("每节高度 ${height.roundToInt()} dp");Slider(height,{height=it},valueRange=44f..120f)
        Text("圆角 ${corner.roundToInt()} dp");Slider(corner,{corner=it},valueRange=0f..24f)
        Text("课程卡片不透明度 ${(opacity*100).roundToInt()}%");Slider(opacity,{opacity=it},valueRange=0f..1f)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("显示周末");Switch(weekends,{weekends=it})}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("显示教师");Switch(teacher,{teacher=it})}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("显示上课时间");Switch(times,{times=it})}
        OutlinedTextField(color,{color=it},label={Text("背景颜色 #RRGGBB")},isError=!validColor,modifier=Modifier.fillMaxWidth(),singleLine=true)
        ActionRow("选择背景图片" to pickBackground,"移除背景图片" to {background=null})
        if(background!=null)Text("已选择背景；照片清理时默认保护课表背景。",style=MaterialTheme.typography.bodySmall)
        Button(onClick={val values=payload();run{val result=execute(operation("settings.update",values=values));onClose();result}},enabled=validColor,modifier=Modifier.fillMaxWidth()){Text("保存显示设置")}
        TextButton(onClick={font=12f;height=64f;corner=8f;opacity=.85f;weekends=true;teacher=false;times=true;color="#F3F4FC";background=null}){Text("恢复默认预览")}
        Spacer(Modifier.height(24.dp))
    }}
}
