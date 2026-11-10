package org.portlandkollel.kiosk

import org.json.JSONObject
import org.portlandkollel.kiosk.core.*
import java.util.UUID

class TabletLedger(private val vault:StandaloneVault) {
    companion object { val TERMINAL=setOf("COMPLETED","FAILED","CANCELED") }
    fun active():JSONObject? {
        val root=vault.read();val id=root.optString("active")
        return records(root).find {it.getString("id")==id}
    }
    fun records(root:JSONObject=vault.read()):List<JSONObject> {
        val values=root.getJSONArray("records")
        return (0 until values.length()).map {values.getJSONObject(it)}
    }
    /** [cents] is the full charge; [feeCents] is the donor-covered card fee included in it (gift = cents - feeCents). */
    fun reserve(cents:Long,details:GivingDetails,location:String,environment:String,feeCents:Long=0):JSONObject=vault.update {root ->
        check(root.optString("active").isEmpty());DonationPolicy().validate(cents);details.validate()
        check(feeCents in 0 until cents)
        val donor=details.normalized()
        val record=JSONObject().put("id",UUID.randomUUID().toString()).put("sdk_attempt_id",UUID.randomUUID().toString()).put("cents",cents)
            .put("fee_cents",feeCents).put("gift_cents",cents-feeCents)
            .put("created",System.currentTimeMillis()).put("environment",environment).put("location",location).put("status","RESERVED")
            .put("details",JSONObject().put("name",donor.name).put("email",donor.email).put("honor",donor.honor).put("anonymous",donor.anonymous).put("thank_you",donor.thankYou)
                .put("first_name",donor.firstName).put("last_name",donor.lastName).put("phone",donor.phone)
                .put("street",donor.street).put("city",donor.city).put("state",donor.state).put("zip",donor.zip))
            .put("sheet_done",false).put("email_status",if(donor.thankYou)"PENDING" else "NONE").put("staff_notice","PENDING")
        root.getJSONArray("records").put(record);root.put("active",record.getString("id"));JSONObject(record.toString())
    }
    fun importLegacy(dispatch:SquareDispatch,details:JSONObject,location:String,environment:String,created:Long,requestKey:String?):JSONObject=vault.update {root ->
        check(root.optString("active").isEmpty())
        check(records(root).none {it.getString("id")==dispatch.referenceId})
        val donor=JSONObject(details.toString()).put("thank_you",false)
        val record=JSONObject().put("id",dispatch.referenceId).put("sdk_attempt_id",dispatch.sdkAttemptId).put("cents",dispatch.cents)
            .put("created",created).put("environment",environment).put("location",location).put("status","UNKNOWN")
            .put("details",donor).put("sheet_done",false).put("email_status","NONE")
        if(requestKey!=null)record.put("legacy_request_key",requestKey)
        root.getJSONArray("records").put(record);root.put("active",dispatch.referenceId)
        JSONObject(record.toString())
    }
    fun change(id:String,block:(JSONObject)->Unit)=vault.update {root ->
        val row=records(root).single {it.getString("id")==id}
        val immutable=listOf("id","sdk_attempt_id","cents","created","environment","location","details")
        val before=immutable.map {row.get(it).toString()};block(row)
        check(immutable.map {row.get(it).toString()}==before) {"IMMUTABLE_DONATION"}
    }
    fun verified(id:String,payment:JSONObject)=vault.update {root ->
        val row=records(root).single {it.getString("id")==id}
        val status=payment.getString("status")
        check(records(root).none {it.getString("id")!=id && it.optString("environment")==row.getString("environment") && it.optString("payment_id")==payment.getString("id")}) {"PAYMENT_ALREADY_RECORDED"}
        check(row.optString("payment_id").isEmpty() || row.getString("payment_id")==payment.getString("id"))
        if(row.getString("status") in setOf("COMPLETED","FAILED","CANCELED"))check(row.getString("status")==status)
        row.put("payment_id",payment.getString("id")).put("status",status).put("square_verified",true)
        if(status in TERMINAL)finalize(root,row,payment.optString("created_at",java.time.Instant.now().toString()))
    }
    /** Every terminal outcome (completed, failed, canceled) gets one Sheet row; only completed gifts email the donor. */
    private fun finalize(root:JSONObject,row:JSONObject,timestamp:String) {
        // Completed gifts also get the next row (and K-receipt number) on the QuickBooks import tab.
        if(row.getString("status")=="COMPLETED" && !row.has("qb_row")) {
            val next=root.optInt("next_qb_row",2);row.put("qb_row",next);root.put("next_qb_row",next+1)
        }
        if(!row.has("sheet_row")) {
            row.put("sheet_row",root.getInt("next_row"));root.put("next_row",root.getInt("next_row")+1)
            row.put("confirmed_date",java.time.Instant.parse(timestamp).atZone(java.time.ZoneId.of("America/Los_Angeles")).toOffsetDateTime().toString())
        }
        if(row.getString("status")!="COMPLETED" && row.optString("email_status")=="PENDING")row.put("email_status","NONE")
    }
    /** Donor canceled and Square confirmed no payment exists for this reference: close without a charge. */
    fun closeNoCharge(id:String)=vault.update {root ->
        check(root.getString("active")==id)
        val row=records(root).single {it.getString("id")==id}
        check(row.optBoolean("sdk_no_payment") && row.optString("payment_id").isEmpty() && row.getString("status") !in setOf("COMPLETED","FAILED","CANCELED"))
        row.put("status","CANCELED").put("square_verified",true).put("no_charge",true)
        finalize(root,row,java.time.Instant.now().toString())
        root.remove("active")
    }
    fun close(id:String)=vault.update {root ->
        check(root.getString("active")==id)
        val record=records(root).single {it.getString("id")==id}
        check(record.optBoolean("square_verified") && record.getString("status") in setOf("COMPLETED","FAILED","CANCELED"))
        root.remove("active") // Ledger and independent outbox remain forever; no charge replay.
    }
}
