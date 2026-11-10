package org.portlandkollel.kiosk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import org.portlandkollel.kiosk.core.*

private val Ink = Color(0xFF193E70)
private val Blue = Color(0xFF214D85)
private val Red = Color(0xFFCC4B32)
private val Paper = Color(0xFFFAF8F5)
private val Line = Color(0xFFE5E4E1)
private val Muted = Color(0xFF596674)
private val Soft = Color(0xFFEDF1F6)
private val Inter = FontFamily(Font(R.font.inter))
private val Condensed = FontFamily(Font(R.font.barlow_condensed,FontWeight.SemiBold))
private val Shape = RoundedCornerShape(18.dp)

/**
 * Portrait kiosk layout for the Pixel Tablet (800×1280 dp upright); wider screens center the same column.
 * Visual source: docs/design/kiosk-portrait-mockup.html. Never owns SDK or journal state.
 */
@Composable
fun GivingScreen(
    dollars:String,onDollars:(String)->Unit,busy:Boolean,locked:Boolean,authorized:Boolean,
    message:String,verifiedStatus:String?,onPay:()->Unit,onRecover:()->Unit,
    state: GivingState, onFinish:()->Unit, onStaffRequest:()->Unit={}, setupContent:@Composable ()->Unit,
) {
    var page by remember(state.formEpoch) { mutableIntStateOf(0) }
    var first by remember(state.formEpoch) { mutableStateOf("") }
    var last by remember(state.formEpoch) { mutableStateOf("") }
    var email by remember(state.formEpoch) { mutableStateOf("") }
    var phone by remember(state.formEpoch) { mutableStateOf("") }
    var street by remember(state.formEpoch) { mutableStateOf("") }
    var city by remember(state.formEpoch) { mutableStateOf("") }
    var region by remember(state.formEpoch) { mutableStateOf("") }
    var zip by remember(state.formEpoch) { mutableStateOf("") }
    var honor by remember(state.formEpoch) { mutableStateOf("") }
    var anonymous by remember(state.formEpoch) { mutableStateOf(false) }
    var validation by remember(state.formEpoch) { mutableStateOf("") }
    var fresh by remember(state.formEpoch) { mutableStateOf(true) }
    val tools=state.staffUnlocked
    val editable=!busy&&!locked
    val giftCents=runCatching { DonationPolicy().parse(dollars) }.getOrNull()
    val valid=giftCents!=null
    val feeCents=if(state.coverFee&&giftCents!=null) runCatching { FeePolicy(state.feeBps,state.feeFixed).coverFor(giftCents) }.getOrDefault(0L) else 0L
    fun fmt(cents:Long)=GivingSafety.formatCents(cents)
    val gift=fmt(giftCents?:0);val total=fmt((giftCents?:0)+feeCents)
    val wholeTotal=((giftCents?:0)+feeCents).let { if(it%100==0L) "$"+(it/100) else fmt(it) }
    LaunchedEffect(verifiedStatus) { if(verifiedStatus!=null)page=3 }
    MaterialTheme(colorScheme=lightColorScheme(primary=Blue,secondary=Red,background=Paper,surface=Color.White,onSurface=Ink),
        typography=Typography(bodyLarge=MaterialTheme.typography.bodyLarge.copy(fontFamily=Inter),
            bodyMedium=MaterialTheme.typography.bodyMedium.copy(fontFamily=Inter),
            labelLarge=MaterialTheme.typography.labelLarge.copy(fontFamily=Inter))) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),color=Paper,contentColor=Ink) {
            // Designed at 800×1280 dp (Pixel Tablet upright); smaller screens scale the whole layout down uniformly.
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val scale=minOf(1f,maxWidth/800.dp,maxHeight/1280.dp)
            val base=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density*scale,base.fontScale)) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
                Column(Modifier.fillMaxHeight().widthIn(max=820.dp).fillMaxWidth()) {
                    // Header: hidden staff entry = press and hold the logo for 3 seconds.
                    Row(Modifier.fillMaxWidth().background(Color.White).height(104.dp).padding(horizontal=36.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Image(painterResource(R.drawable.portland_kollel_logo),"Portland Kollel — Discover your Jewish connection",
                            Modifier.height(76.dp).width(195.dp).pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown()
                                    if(withTimeoutOrNull(3000L) {waitForUpOrCancellation()}==null)onStaffRequest()
                                }
                            })
                        Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                            (0..2).forEach { index ->
                                val done=index<page.coerceAtMost(3)||page==3;val current=index==page&&page<3
                                Surface(shape=CircleShape,color=if(current)Blue else if(done)Soft else Color.White,
                                    border=BorderStroke(2.dp,if(current)Blue else if(done)Soft else Color(0xFFD3D9DF))) {
                                    Box(Modifier.size(38.dp),contentAlignment=Alignment.Center) {
                                        Text(if(done)"✓" else "${index+1}",fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=if(current)Color.White else if(done)Blue else Muted)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color=Line)
                    // Only Sandbox and setup modes are labeled; live donors see no status line.
                    if(state.sandbox || !state.live || tools)Text(if(state.sandbox) "SQUARE SANDBOX · No real money" else if(state.live) "LIVE · Real card payment" else "READER SETUP · Real donations disabled",
                        fontSize=14.sp,color=Red,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(horizontal=36.dp,vertical=6.dp))
                    val body=Modifier.fillMaxWidth().weight(1f).padding(horizontal=36.dp,vertical=28.dp)
                    when {
                        tools -> Column(body.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            if(message.isNotEmpty())Surface(shape=Shape,color=Soft) {Text(message,Modifier.padding(16.dp),fontSize=16.sp)}
                            setupContent()
                        }
                        locked && verifiedStatus==null -> Column(body,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                            Heading("One moment",60,TextAlign.Center)
                            Text(donorMessage(message),fontSize=22.sp,color=Muted,textAlign=TextAlign.Center,lineHeight=32.sp)
                            Spacer(Modifier.height(36.dp))
                            OutlinedButton(onClick=onRecover,enabled=!busy,shape=Shape,modifier=Modifier.heightIn(min=80.dp)) {
                                Text(if(busy)"Checking…" else "Check payment status",fontSize=20.sp)
                            }
                        }
                        page==0 -> Column(body) {
                            Eyebrow("A little giving. A lasting impact.")
                            Heading("Please donate.\nMake a difference.",64)
                            Text("Support Torah learning and Jewish connection in our community.",fontSize=21.sp,color=Muted,lineHeight=30.sp)
                            Row(Modifier.fillMaxWidth().padding(top=18.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.Top) {
                                Text("$",fontSize=44.sp,color=Red,modifier=Modifier.padding(top=18.dp,end=10.dp))
                                Text(dollars.ifEmpty { "0" },fontSize=104.sp,lineHeight=110.sp,fontWeight=FontWeight.Medium,letterSpacing=(-3).sp)
                            }
                            HorizontalDivider(thickness=4.dp,color=Red)
                            Spacer(Modifier.height(22.dp))
                            val presets=listOf("18","36","54","108","180","1")
                            presets.chunked(3).forEach { rowKeys ->
                                Row(Modifier.padding(bottom=14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                    rowKeys.forEach { preset ->
                                        val selected=dollars==preset
                                        OutlinedButton(onClick={onDollars(preset);fresh=true},enabled=editable,shape=Shape,
                                            colors=ButtonDefaults.outlinedButtonColors(containerColor=if(selected)Blue else Color.White,contentColor=if(selected)Color.White else Blue),
                                            border=BorderStroke(2.dp,if(selected)Blue else Color(0xFFD4DBE4)),contentPadding=PaddingValues(0.dp),
                                            modifier=Modifier.weight(1f).height(76.dp)) {
                                            if(preset=="1")Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                                Text("☕ $1",fontSize=22.sp,fontWeight=FontWeight.SemiBold);Text("Just a coffee",fontSize=13.sp)
                                            } else Text("$$preset",fontSize=26.sp,fontWeight=FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                            Column(Modifier.weight(1f).padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("Clear","0","⌫")).forEach { keys ->
                                    Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                        keys.forEach { key ->
                                            val digit=key.length==1&&key!="⌫"
                                            OutlinedButton(onClick={
                                                when(key) {
                                                    "Clear" -> {onDollars("");fresh=true}
                                                    "⌫" -> {onDollars(dollars.dropLast(1));fresh=false}
                                                    else -> {
                                                        val next=if(fresh)key else dollars+key
                                                        if(next.length<=5&&(next.toLongOrNull()?:10001)<=10000) {onDollars((next.toLongOrNull()?:0).toString());fresh=false}
                                                    }
                                                }
                                            },enabled=editable,shape=Shape,colors=ButtonDefaults.outlinedButtonColors(containerColor=if(digit)Color.White else Color.Transparent),
                                                border=BorderStroke(2.dp,if(digit)Line else Color.Transparent),
                                                contentPadding=PaddingValues(0.dp),modifier=Modifier.weight(1f).fillMaxHeight()) {
                                                Text(key,fontSize=if(digit)34.sp else 20.sp,fontWeight=FontWeight.Normal,color=if(digit)Ink else Muted)
                                            }
                                        }
                                    }
                                }
                            }
                            Primary("Continue  →",editable&&valid,Modifier.fillMaxWidth().padding(top=24.dp)) { page=1 }
                        }
                        page==1 -> Column(body.verticalScroll(rememberScrollState()).imePadding()) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                TextButton(onClick={page=0},enabled=editable,contentPadding=PaddingValues(0.dp)) { Text("← Change amount",fontSize=20.sp,fontWeight=FontWeight.SemiBold) }
                                Surface(shape=RoundedCornerShape(30.dp),color=Soft) { Text("Your gift · $gift",Modifier.padding(horizontal=18.dp,vertical=8.dp),fontSize=20.sp) }
                            }
                            Heading("Who can we thank?",52)
                            if(anonymous) Notice("⚠️ Anonymous gifts will not receive a year-end tax-deductible receipt.",warning=true)
                            else Notice("We love to thank our donors. To receive your year-end tax-deductible receipt, please fill in your name, email, cell and address.")
                            Row(Modifier.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(anonymous,{anonymous=it},enabled=editable,modifier=Modifier.scale(1.3f).padding(end=4.dp))
                                Text("Give anonymously",fontSize=22.sp,modifier=Modifier.padding(start=8.dp))
                            }
                            val open=editable&&!anonymous
                            Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                                Field(first,{first=it.take(60)},"First name",open,Modifier.weight(1f),words=true)
                                Field(last,{last=it.take(60)},"Last name",open,Modifier.weight(1f),words=true)
                            }
                            Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                                Field(email,{email=it.take(200)},"Email",open,Modifier.weight(1f),KeyboardType.Email,small=true)
                                Field(phone,{phone=it.take(25)},"Cell phone",open,Modifier.weight(1f),KeyboardType.Phone,small=true)
                            }
                            Field(street,{street=it.take(200)},"Street address",open,Modifier.fillMaxWidth(),words=true)
                            Row(horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                                Field(city,{city=it.take(80)},"City",open,Modifier.weight(2f),words=true)
                                Field(region,{region=it.take(40)},"State",open,Modifier.weight(1f),words=true)
                                Field(zip,{zip=it.take(12)},"ZIP",open,Modifier.weight(1.2f),KeyboardType.Number)
                            }
                            Field(honor,{honor=it.take(500)},"In honor of · optional",editable,Modifier.fillMaxWidth(),words=false)
                            if(state.sandbox)Text("Use fictitious details for this Sandbox test.",fontSize=18.sp,color=Red)
                            if(validation.isNotEmpty())Text(validation,color=Red,fontSize=18.sp)
                            Primary("Review your gift  →",editable,Modifier.fillMaxWidth().padding(top=18.dp)) {
                                val donor=GivingDetails("",email,honor,anonymous,!anonymous&&email.isNotBlank(),first,last,phone,street,city,region,zip).normalized()
                                runCatching { donor.validate() }.onSuccess { state.details=donor;validation="";page=2 }
                                    .onFailure { validation=it.message?:"Please check your details." }
                            }
                        }
                        page==2 -> Column(body) {
                            TextButton(onClick={page=1},enabled=editable,contentPadding=PaddingValues(0.dp)) { Text("← Edit details",fontSize=20.sp,fontWeight=FontWeight.SemiBold) }
                            Heading("Ready to give?",60)
                            val d=state.details
                            Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=Color.White,border=BorderStroke(2.dp,Line)) {
                                Column(Modifier.padding(horizontal=36.dp,vertical=30.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                                    ReviewRow("Name",if(d.anonymous)"Anonymous" else d.name.ifEmpty { "Not provided" })
                                    if(d.email.isNotEmpty())ReviewRow("Email",d.email)
                                    if(d.honor.isNotBlank())ReviewRow("In honor of",d.honor)
                                    ReviewRow("Your gift",gift)
                                    if(feeCents>0)ReviewRow("Card processing",fmt(feeCents))
                                    HorizontalDivider(color=Line,thickness=2.dp);ReviewRow("Total",total,true)
                                }
                            }
                            Surface(Modifier.fillMaxWidth().padding(top=24.dp).clickable(enabled=editable) { state.coverFee=!state.coverFee },
                                shape=RoundedCornerShape(22.dp),color=Color.White,border=BorderStroke(3.dp,Blue)) {
                                Row(Modifier.padding(horizontal=24.dp,vertical=22.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Checkbox(state.coverFee,{state.coverFee=it},enabled=editable,modifier=Modifier.scale(1.3f).padding(end=4.dp))
                                    Column(Modifier.padding(start=14.dp)) {
                                        Text("Help cover card processing",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                                        val add=giftCents?.let { runCatching { fmt(FeePolicy(state.feeBps,state.feeFixed).coverFor(it)) }.getOrNull() }?:""
                                        // Donors see only the dollar amount, never the percentage.
                                        val giftWhole=giftCents?.let { if(it%100==0L) "$"+(it/100) else fmt(it) }?:gift
                                        Text("Add $add so the Kollel receives the full $giftWhole.",fontSize=18.sp,color=Muted,lineHeight=26.sp)
                                    }
                                }
                            }
                            Text(if(d.anonymous)"Thank you for your anonymous gift." else "Your tax-deductible receipt will be sent at the end of the year.",
                                fontSize=19.sp,color=Muted,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=22.dp))
                            if(state.sandbox)Text("Square’s Sandbox screen opens next. Use the simulated reader. No real money is collected.",fontSize=18.sp,color=Red,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=8.dp))
                            Spacer(Modifier.weight(1f))
                            Primary(if(busy)"Working…" else if(state.sandbox) "Give in Square Sandbox" else if(state.live) "Give $wholeTotal  ♥" else "Real donations disabled",
                                editable&&authorized&&valid&&(state.sandbox||state.live),onClick=onPay)
                        }
                        else -> Column(body.clickable(enabled=!busy&&verifiedStatus!=null) { onFinish() },horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                            val completed=verifiedStatus=="COMPLETED";val d=state.details
                            Surface(shape=CircleShape,color=Soft) {Box(Modifier.size(150.dp),contentAlignment=Alignment.Center) {Text(if(completed)"♥" else "✕",fontSize=76.sp,color=Red)}}
                            Spacer(Modifier.height(26.dp))
                            val firstName=d.firstName.ifEmpty { d.name.substringBefore(' ') }
                            Heading(if(!completed)"Gift not completed" else if(d.anonymous||firstName.isEmpty())"Thank you!" else "Thank you,\n$firstName!",if(completed)96 else 68,TextAlign.Center)
                            Text(if(completed)"Your gift of $total supports Torah learning and Jewish connection in our community." else "No charge was made. You are welcome to try again.",
                                fontSize=26.sp,color=Muted,textAlign=TextAlign.Center,lineHeight=38.sp)
                            if(completed&&!d.anonymous)Text("Your tax-deductible receipt will be sent to you at the end of the year.",fontSize=22.sp,color=Muted,textAlign=TextAlign.Center,lineHeight=32.sp,modifier=Modifier.padding(top=20.dp))
                            if(completed&&state.sandbox)Text("Sandbox test · No real money was collected.",fontSize=20.sp,color=Red,modifier=Modifier.padding(top=12.dp))
                            Surface(Modifier.padding(top=40.dp),shape=RoundedCornerShape(40.dp),color=Color.White,border=BorderStroke(2.dp,Line)) {
                                Text("Starting over in 15 seconds · Tap to start now",Modifier.padding(horizontal=26.dp,vertical=14.dp),fontSize=19.sp,color=Muted)
                            }
                        }
                    }
                }
            }
            }
            }
        }
    }
}

/** Donor-facing wording for in-progress states; staff codes stay in the staff menu. */
private fun donorMessage(message:String)=when {
    message.startsWith("Payment not completed") -> "Payment canceled. Confirming that no charge was made…"
    message.startsWith("Could not yet confirm") -> "We could not confirm the payment yet. Please ask a staff member, or tap below to check again."
    else -> "We’re confirming your payment with Square. Your gift is saved."
}

@Composable private fun Eyebrow(text:String) {Text(text.uppercase(Locale.US),fontSize=15.sp,letterSpacing=3.sp,fontWeight=FontWeight.Bold,color=Red)}
@Composable private fun Heading(text:String,size:Int,align:TextAlign=TextAlign.Start) {
    Text(text.uppercase(Locale.US),fontFamily=Condensed,fontSize=size.sp,lineHeight=(size*.96).sp,letterSpacing=.2.sp,color=Blue,textAlign=align,modifier=Modifier.padding(top=12.dp,bottom=12.dp))
}
@Composable private fun Primary(text:String,enabled:Boolean,modifier:Modifier=Modifier.fillMaxWidth(),onClick:()->Unit={}) {
    Button(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(22.dp),colors=ButtonDefaults.buttonColors(containerColor=Red,disabledContainerColor=Color(0xFFD9BCB4),disabledContentColor=Color.White),
        modifier=modifier.height(96.dp),contentPadding=PaddingValues(horizontal=24.dp)) {Text(text,fontSize=28.sp,fontWeight=FontWeight.SemiBold)}
}
@Composable private fun Notice(text:String,warning:Boolean=false) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=if(warning)Color(0xFFFDF0EC) else Color.White,
        border=BorderStroke(2.dp,if(warning)Color(0xFFF2C7BB) else Line)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(8.dp).fillMaxHeight().background(if(warning)Red else Blue))
            Text(text,Modifier.padding(horizontal=20.dp,vertical=16.dp),fontSize=19.sp,lineHeight=28.sp,color=if(warning)Color(0xFF8A2E1C) else Ink,
                fontWeight=if(warning)FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}
@Composable private fun Field(value:String,onChange:(String)->Unit,label:String,enabled:Boolean,modifier:Modifier,
                              type:KeyboardType=KeyboardType.Text,small:Boolean=false,words:Boolean=false) {
    OutlinedTextField(value,onChange,enabled=enabled,label={Text(label,fontSize=16.sp)},singleLine=true,
        textStyle=TextStyle(fontSize=if(small)20.sp else 22.sp,fontFamily=Inter,color=Ink),shape=Shape,
        keyboardOptions=KeyboardOptions(keyboardType=type,capitalization=if(words)KeyboardCapitalization.Words else KeyboardCapitalization.None),
        modifier=modifier.padding(bottom=12.dp).heightIn(min=72.dp))
}
@Composable private fun ReviewRow(label:String,value:String,total:Boolean=false) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text(label,fontSize=if(total)40.sp else 24.sp,fontWeight=if(total)FontWeight.SemiBold else FontWeight.Normal,color=if(total)Ink else Muted,modifier=Modifier.weight(1f))
        Text(value,fontSize=if(total)40.sp else 24.sp,fontWeight=if(total)FontWeight.SemiBold else FontWeight.Normal,textAlign=TextAlign.End,modifier=Modifier.weight(1.4f))
    }
}
