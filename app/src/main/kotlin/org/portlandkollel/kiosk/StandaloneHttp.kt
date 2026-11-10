package org.portlandkollel.kiosk

import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection

class RemoteFailure(val status:Int):Exception("REMOTE_HTTP_$status")
object StandaloneHttp {
    fun request(url:String,method:String="GET",token:String?=null,body:JSONObject?=null,callbackOrigin:String?=null):JSONObject {
        val uri=URI(url)
        check(uri.scheme=="https" && uri.userInfo==null && uri.fragment==null)
        val trusted=uri.host in setOf("connect.squareup.com","connect.squareupsandbox.com","sheets.googleapis.com","gmail.googleapis.com","www.googleapis.com") || (callbackOrigin!=null && uri.originForCallback()==callbackOrigin && uri.path.matches(Regex("/(sandbox|production)/reader/(sessions|poll)")))
        check(trusted && (uri.port==-1 || uri.port==443))
        val c=uri.toURL().openConnection() as HttpsURLConnection
        c.instanceFollowRedirects=false;c.connectTimeout=15000;c.readTimeout=25000;c.requestMethod=method
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("Square-Version","2026-09-16")
        if(token!=null)c.setRequestProperty("Authorization","Bearer $token")
        try {
            if(body!=null) {c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}}
            val status=c.responseCode
            if(status !in 200..299)throw RemoteFailure(status) // Never print error bodies/URLs/credentials.
            val bytes=c.inputStream.use {input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true) {val n=input.read(buffer);if(n<0)break;check(output.size()+n<=1048576);output.write(buffer,0,n)}
                output.toByteArray()
            }
            return if(bytes.isEmpty())JSONObject() else JSONObject(String(bytes,Charsets.UTF_8))
        } finally {c.disconnect()}
    }
    private fun URI.originForCallback()="https://$host"
    fun callbackOrigin(value:String):String {
        val uri=URI(value)
        require(uri.scheme=="https" && uri.userInfo==null && uri.port==-1 && uri.rawQuery==null && uri.fragment==null && (uri.path.isEmpty() || uri.path=="/")) {"CLOUDFLARE_CALLBACK_ORIGIN_REQUIRED"}
        require(uri.host!=null && uri.host.matches(Regex("[a-z0-9-]+\\.[a-z0-9-]+\\.workers\\.dev")) && !uri.host.startsWith("configure-")) {"CLOUDFLARE_CALLBACK_ORIGIN_REQUIRED"}
        return "https://${uri.host}"
    }
    fun encode(value:String)=java.net.URLEncoder.encode(value,"UTF-8")
}

internal typealias TabletHttp=(String,String,String?,JSONObject?)->JSONObject
internal fun defaultTabletHttp(url:String,method:String,token:String?,body:JSONObject?)=StandaloneHttp.request(url,method,token,body)
