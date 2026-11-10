package org.portlandkollel.kiosk.core

/** Facts fetched from Square over authenticated HTTPS; SDK callbacks alone are hints. */
data class SquarePaymentProof(val id:String,val reference:String,val location:String,val cents:Long,val currency:String,val status:String,val source:String) {
    fun confirm(expected:SquareDispatch,expectedLocation:String):String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,192}")))
        check(reference==expected.referenceId && location==expectedLocation && cents==expected.cents && currency=="USD" && source=="CARD") { "SQUARE_PAYMENT_MISMATCH" }
        check(status in setOf("APPROVED","PENDING","COMPLETED","CANCELED","FAILED"))
        return status
    }
}
