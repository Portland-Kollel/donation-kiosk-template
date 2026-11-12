package org.portlandkollel.kiosk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val StaffInk=Color(0xFF193E70);private val StaffBlue=Color(0xFF214D85);private val StaffRed=Color(0xFFCC4B32)
private val StaffLine=Color(0xFFE5E4E1);private val StaffMuted=Color(0xFF596674);private val StaffSoft=Color(0xFFEDF1F6)
private val StaffGreen=Color(0xFF2E7D4F);private val StaffAmber=Color(0xFFB7791F)
private val StaffTitle=FontFamily(Font(R.font.barlow_condensed,FontWeight.SemiBold))
private val CardShape=RoundedCornerShape(18.dp)

internal class StaffAction(val label:String,val enabled:Boolean=true,val danger:Boolean=false,val primary:Boolean=false,val onClick:()->Unit)
internal class StaffSection(val title:String,val note:String,val actions:List<StaffAction>)

/** Staff menu: live status at the top, a no-charge self-test, then grouped actions. Shown only after the staff PIN. */
@Composable
internal fun StaffMenu(status:List<StaffCheck>,selfTest:List<StaffCheck>?,testing:Boolean,busy:Boolean,
                       onSelfTest:()->Unit,onClose:()->Unit,sections:List<StaffSection>) {
    Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("STAFF MENU",fontFamily=StaffTitle,fontSize=40.sp,color=StaffBlue)
                Text("Locks again by itself after 1 minute.",fontSize=15.sp,color=StaffMuted)
            }
            OutlinedButton(onClick=onClose,shape=RoundedCornerShape(14.dp),border=BorderStroke(2.dp,StaffBlue),modifier=Modifier.height(56.dp)) {
                Text("Close  ✕",fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=StaffBlue)
            }
        }
        StaffCard("Status",if(status.all {it.level==StaffCheck.Level.OK})"Everything looks good." else "Items marked ! or ✕ need a look.") {
            status.forEach {CheckRow(it)}
        }
        StaffCard("Self-test","Checks Square, the card reader, Google and the kiosk lock, and emails a test message to staff. No card is charged.") {
            Button(onClick=onSelfTest,enabled=!busy,shape=RoundedCornerShape(14.dp),
                colors=ButtonDefaults.buttonColors(containerColor=StaffBlue),modifier=Modifier.fillMaxWidth().height(64.dp)) {
                Text(if(testing)"Testing…" else "Run self-test",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            }
            selfTest?.let {results ->
                val failed=results.count {it.level==StaffCheck.Level.FAIL}
                Text(if(failed==0)"Self-test passed." else "Self-test found $failed problem${if(failed==1)"" else "s"}.",
                    fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=if(failed==0)StaffGreen else StaffRed)
                results.forEach {CheckRow(it)}
            }
        }
        sections.forEach {section -> StaffCard(section.title,section.note) {ActionGrid(section.actions)}}
    }
}

@Composable private fun StaffCard(title:String,note:String,content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),shape=CardShape,color=Color.White,border=BorderStroke(2.dp,StaffLine)) {
        Column(Modifier.padding(horizontal=24.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,fontSize=22.sp,fontWeight=FontWeight.Bold,color=StaffInk)
            if(note.isNotEmpty())Text(note,fontSize=15.sp,color=StaffMuted,lineHeight=21.sp)
            content()
        }
    }
}

@Composable private fun CheckRow(check:StaffCheck) {
    val (mark,color)=when(check.level) {StaffCheck.Level.OK->"✓" to StaffGreen;StaffCheck.Level.WARN->"!" to StaffAmber;StaffCheck.Level.FAIL->"✕" to StaffRed}
    Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Surface(shape=CircleShape,color=color.copy(alpha=0.12f)) {
            Box(Modifier.size(32.dp),contentAlignment=Alignment.Center) {Text(mark,fontSize=17.sp,fontWeight=FontWeight.Bold,color=color)}
        }
        Column(Modifier.weight(1f)) {
            Text(check.label,fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=StaffInk)
            Text(check.detail,fontSize=15.sp,color=StaffMuted,lineHeight=21.sp)
        }
    }
}

/** Two buttons per row, equal width. */
@Composable private fun ActionGrid(actions:List<StaffAction>) {
    actions.chunked(2).forEach {row ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            row.forEach {action ->
                val modifier=Modifier.weight(1f).heightIn(min=60.dp)
                when {
                    action.danger -> OutlinedButton(onClick=action.onClick,enabled=action.enabled,shape=RoundedCornerShape(14.dp),
                        border=BorderStroke(2.dp,StaffRed.copy(alpha=0.5f)),modifier=modifier) {Text(action.label,fontSize=16.sp,color=StaffRed)}
                    action.primary -> Button(onClick=action.onClick,enabled=action.enabled,shape=RoundedCornerShape(14.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=StaffBlue),modifier=modifier) {Text(action.label,fontSize=16.sp,fontWeight=FontWeight.SemiBold)}
                    else -> OutlinedButton(onClick=action.onClick,enabled=action.enabled,shape=RoundedCornerShape(14.dp),
                        colors=ButtonDefaults.outlinedButtonColors(containerColor=StaffSoft.copy(alpha=0.5f)),
                        border=BorderStroke(1.5.dp,Color(0xFFD4DBE4)),modifier=modifier) {Text(action.label,fontSize=16.sp,color=StaffBlue)}
                }
            }
            if(row.size==1)Spacer(Modifier.weight(1f))
        }
    }
}
