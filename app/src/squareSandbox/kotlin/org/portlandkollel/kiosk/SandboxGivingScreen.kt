package org.portlandkollel.kiosk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val Ink = Color(0xFF193E70)
private val Blue = Color(0xFF214D85)
private val Red = Color(0xFFCC4B32)
private val Paper = Color(0xFFFAF8F5)
private val Line = Color(0xFFE5E4E1)
private val Muted = Color(0xFF596674)
private val Inter = FontFamily(Font(R.font.inter))
private val Condensed = FontFamily(Font(R.font.barlow_condensed,FontWeight.SemiBold))
private val Shape = RoundedCornerShape(11.dp)

/** Visual source: docs/design/kollel-giving-preview.html. Never owns SDK or journal state. */
@Composable
fun SandboxGivingScreen(
    dollars:String,onDollars:(String)->Unit,busy:Boolean,locked:Boolean,authorized:Boolean,
    message:String,verifiedStatus:String?,onPay:()->Unit,onRecover:()->Unit,
    setupContent:@Composable ()->Unit,
) {
    var page by remember { mutableIntStateOf(0) }
    var fresh by remember { mutableStateOf(true) }
    var tools by remember { mutableStateOf(!authorized) }
    val editable=!busy&&!locked
    val valid=dollars.toLongOrNull()?.let { it in 1..10000 }==true
    val amount="$"+String.format(Locale.US,"%,d",dollars.toLongOrNull()?:0)+".00"
    LaunchedEffect(verifiedStatus) { if(verifiedStatus!=null)page=3 }
    MaterialTheme(colorScheme=lightColorScheme(primary=Blue,secondary=Red,background=Paper,surface=Color.White,onSurface=Ink),
        typography=Typography(bodyLarge=MaterialTheme.typography.bodyLarge.copy(fontFamily=Inter),
            bodyMedium=MaterialTheme.typography.bodyMedium.copy(fontFamily=Inter),
            labelLarge=MaterialTheme.typography.labelLarge.copy(fontFamily=Inter))) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),color=Paper,contentColor=Ink) {
            BoxWithConstraints {
                val wide=maxWidth>=650.dp
                val side=if(wide)36.dp else 22.dp
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=side,vertical=20.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Image(painterResource(R.drawable.portland_kollel_logo),"Portland Kollel — Discover your Jewish connection",
                            Modifier.width(if(wide)210.dp else 180.dp).height(if(wide)82.dp else 70.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(if(wide)17.dp else 10.dp)) {
                            listOf("Your gift","Your details","Give").forEachIndexed { index,label ->
                                val current=index==page.coerceAtMost(2)
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                                    Surface(shape=CircleShape,color=if(current)Blue else Color.White,
                                        border=BorderStroke(1.dp,if(current)Blue else Color(0xFFD3D9DF))) {
                                        Box(Modifier.size(25.dp),contentAlignment=Alignment.Center) {
                                            Text("${index+1}",fontSize=12.sp,color=if(current)Color.White else Muted)
                                        }
                                    }
                                    if(wide)Text(label,fontSize=12.sp,color=if(current)Blue else Muted)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color=Line)
                    Row(Modifier.fillMaxWidth().padding(horizontal=side,vertical=6.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("SQUARE SANDBOX · No real money",fontSize=11.sp,color=Red,fontWeight=FontWeight.SemiBold)
                        TextButton(onClick={tools=!tools}) { Text(if(tools)"Hide test controls" else "Test controls",fontSize=12.sp) }
                    }
                    if(tools)Column(Modifier.fillMaxWidth().padding(horizontal=side),verticalArrangement=Arrangement.spacedBy(8.dp)) { setupContent() }
                    if(locked||busy||!authorized) {
                        Surface(Modifier.fillMaxWidth().padding(horizontal=side,vertical=8.dp),shape=Shape,color=Color(0xFFEDF1F6)) {
                            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(message,fontSize=13.sp)
                                if(locked) {
                                    Text("Original attempt preserved. Another payment is blocked.",fontSize=12.sp)
                                    OutlinedButton(onClick=onRecover,enabled=!busy) { Text("Reconcile original test") }
                                }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal=side,vertical=25.dp)) {
                        when(page) {
                            0 -> {
                                val introduction:@Composable ()->Unit={
                                    Column {
                                        Eyebrow("A little giving. A lasting impact.")
                                        Heading("Please donate.\nMake a difference.",if(wide)52 else 43)
                                        Text("Support Torah learning and Jewish connection in our community.",fontSize=14.sp,color=Muted,lineHeight=23.sp,
                                            modifier=if(wide)Modifier.widthIn(max=320.dp) else Modifier)
                                        Spacer(Modifier.height(24.dp))
                                        Text("Your donation · USD",fontSize=12.sp,color=Muted)
                                        Row(verticalAlignment=Alignment.Top) {
                                            Text("$",fontSize=32.sp,color=Red,modifier=Modifier.padding(top=9.dp,end=7.dp))
                                            Text(String.format(Locale.US,"%,d",dollars.toLongOrNull()?:0),fontSize=if(wide)64.sp else 56.sp,
                                                fontWeight=FontWeight.Medium,letterSpacing=(-3).sp)
                                        }
                                        HorizontalDivider(thickness=2.dp,color=Red)
                                    }
                                }
                                val keypad:@Composable ()->Unit={
                                    Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
                                        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("Clear","0","⌫")).forEach { keys ->
                                            Row(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                                                keys.forEach { key ->
                                                    OutlinedButton(onClick={
                                                        when(key) {
                                                            "Clear" -> {onDollars("");fresh=true}
                                                            "⌫" -> {onDollars(dollars.dropLast(1));fresh=false}
                                                            else -> {
                                                                val next=if(fresh)key else dollars+key
                                                                if(next.length<=5&&(next.toLongOrNull()?:10001)<=10000) {onDollars((next.toLongOrNull()?:0).toString());fresh=false}
                                                            }
                                                        }
                                                    },enabled=editable,shape=Shape,colors=ButtonDefaults.outlinedButtonColors(containerColor=if(key.length==1&&key!="⌫")Color.White else Color.Transparent),
                                                        border=BorderStroke(1.dp,if(key.length==1&&key!="⌫")Line else Color.Transparent),
                                                        contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(1f).heightIn(min=if(wide)63.dp else 56.dp)) {
                                                        Text(key,fontSize=if(key=="Clear")15.sp else 25.sp,fontWeight=FontWeight.Medium)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if(wide)Row(horizontalArrangement=Arrangement.spacedBy(46.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { introduction() };Box(Modifier.weight(1f)) { keypad() }
                                } else Column(verticalArrangement=Arrangement.spacedBy(20.dp)) { introduction();keypad() }
                                Spacer(Modifier.height(26.dp))
                                Text("Quick giving",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement=Arrangement.spacedBy(if(wide)9.dp else 6.dp)) {
                                    listOf("18","36","54","108","180").forEach { preset ->
                                        val selected=dollars==preset
                                        OutlinedButton(onClick={onDollars(preset);fresh=true},enabled=editable,shape=RoundedCornerShape(10.dp),
                                            colors=ButtonDefaults.outlinedButtonColors(containerColor=if(selected)Blue else Color.White,contentColor=if(selected)Color.White else Blue),
                                            border=BorderStroke(1.dp,if(selected)Blue else Color(0xFFD4DBE4)),contentPadding=PaddingValues(0.dp),
                                            modifier=Modifier.weight(1f).heightIn(min=53.dp)) { Text("$$preset",fontSize=if(wide)21.sp else 18.sp,fontWeight=FontWeight.SemiBold) }
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                val coffee:@Composable (Modifier)->Unit={ modifier ->
                                    OutlinedButton(onClick={onDollars("1");fresh=true},enabled=editable,shape=Shape,
                                        colors=ButtonDefaults.outlinedButtonColors(containerColor=if(dollars=="1")Color(0xFFEDF1F6) else Color.White),
                                        border=BorderStroke(if(dollars=="1")2.dp else 1.dp,if(dollars=="1")Blue else Color(0xFFD4DBE4)),
                                        modifier=modifier.heightIn(min=60.dp)) { Text("Just a coffee · $1",fontSize=14.sp) }
                                }
                                if(wide)Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    coffee(Modifier.weight(1f));Primary("Continue  →",editable&&valid,Modifier.weight(1.6f)) { page=1 }
                                } else Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                    coffee(Modifier.fillMaxWidth());Primary("Continue  →",editable&&valid) { page=1 }
                                }
                            }
                            1 -> DetailColumn {
                                TextButton(onClick={page=0},enabled=editable) { Text("← Back to amount") }
                                Eyebrow("Your gift makes a difference");Heading("Who can we thank?",42)
                                Surface(shape=RoundedCornerShape(8.dp),color=Color(0xFFEDF1F6)) { Text("$amount · General donation",Modifier.padding(12.dp),fontSize=13.sp) }
                                Spacer(Modifier.height(18.dp));Text("Your name",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                                OutlinedTextField("Anonymous Sandbox test",{},enabled=false,modifier=Modifier.fillMaxWidth().padding(top=8.dp),shape=Shape)
                                Spacer(Modifier.height(18.dp));Text("Email address · optional",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                                OutlinedTextField("Not collected for this test",{},enabled=false,modifier=Modifier.fillMaxWidth().padding(top=8.dp),shape=Shape)
                                Spacer(Modifier.height(10.dp));Text("Donor details and email receipts are not enabled in Sandbox testing.",fontSize=12.sp,color=Muted)
                                Spacer(Modifier.height(24.dp));Primary("Review your gift  →",editable) {page=2}
                            }
                            2 -> DetailColumn {
                                TextButton(onClick={page=1},enabled=editable) {Text("← Back to your details")}
                                Eyebrow("One last look");Heading("Ready to give?",42)
                                Surface(Modifier.fillMaxWidth().padding(vertical=20.dp),shape=RoundedCornerShape(15.dp),color=Color.White,border=BorderStroke(1.dp,Line)) {
                                    Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                                        ReviewRow("Gift for","General donation");ReviewRow("Name","Anonymous Sandbox test");ReviewRow("Receipt","No email receipt")
                                        HorizontalDivider(color=Line);ReviewRow("Total · USD",amount,true)
                                    }
                                }
                                Text("Square’s Sandbox screen opens next. Use the simulated reader and virtual card. No real money is collected.",fontSize=12.sp,color=Muted,lineHeight=19.sp)
                                Spacer(Modifier.height(24.dp))
                                Primary(if(busy)"Working…" else "Give in Square Sandbox",editable&&authorized&&valid,onClick=onPay)
                            }
                            3 -> DetailColumn {
                                Column(Modifier.fillMaxWidth().padding(vertical=35.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                    Surface(shape=CircleShape,color=Color(0xFFEDF1F6)) {Box(Modifier.size(80.dp),contentAlignment=Alignment.Center) {Text("♡",fontSize=36.sp,color=Blue)}}
                                    Spacer(Modifier.height(18.dp));Eyebrow("Together, we make a difference")
                                    Heading(if(verifiedStatus=="COMPLETED")"Thank you!" else "Payment status",42)
                                    Text("Backend verified Square Sandbox: $verifiedStatus",fontSize=16.sp,textAlign=TextAlign.Center)
                                    Spacer(Modifier.height(10.dp));Text(if(verifiedStatus=="COMPLETED")"Your $amount test gift was verified. No real money was collected and no receipt was sent." else "This is not a completed donation. Preserve the original attempt for reconciliation.",fontSize=14.sp,color=Muted,textAlign=TextAlign.Center)
                                    Spacer(Modifier.height(24.dp));Primary("Test recorded · launch locked",false)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color=Line)
                    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal=side,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("DISCOVER YOUR JEWISH CONNECTION",fontFamily=Condensed,letterSpacing=1.4.sp,fontSize=14.sp,color=Blue)
                        Text("Portland Kollel · Giving together",fontSize=11.sp,color=Muted)
                    }
                }
            }
        }
    }
}

@Composable private fun Eyebrow(text:String) {Text(text.uppercase(Locale.US),fontSize=11.sp,letterSpacing=2.sp,fontWeight=FontWeight.Bold,color=Red)}
@Composable private fun Heading(text:String,size:Int) {Text(text.uppercase(Locale.US),fontFamily=Condensed,fontSize=size.sp,lineHeight=(size*.98).sp,letterSpacing=.2.sp,color=Blue,modifier=Modifier.padding(top=13.dp,bottom=14.dp))}
@Composable private fun Primary(text:String,enabled:Boolean,modifier:Modifier=Modifier.fillMaxWidth(),onClick:()->Unit={}) {
    Button(onClick=onClick,enabled=enabled,shape=Shape,colors=ButtonDefaults.buttonColors(containerColor=Red,disabledContainerColor=Color(0xFFD9BCB4),disabledContentColor=Color.White),
        modifier=modifier.heightIn(min=60.dp),contentPadding=PaddingValues(horizontal=22.dp,vertical=12.dp)) {Text(text,fontSize=17.sp,fontWeight=FontWeight.SemiBold)}
}
@Composable private fun DetailColumn(content:@Composable ColumnScope.()->Unit) {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {Column(Modifier.widthIn(max=530.dp).fillMaxWidth(),content=content)}}
@Composable private fun ReviewRow(label:String,value:String,total:Boolean=false) {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(label,fontSize=if(total)23.sp else 14.sp,modifier=Modifier.weight(1f));Text(value,fontSize=if(total)23.sp else 14.sp,textAlign=TextAlign.Right,modifier=Modifier.weight(1f))}}
