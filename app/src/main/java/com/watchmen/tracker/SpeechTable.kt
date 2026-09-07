package com.watchmen.tracker

object SpeechTable {

    private val table = mapOf(
        "blink" to mapOf(
            "en" to "Please blink to confirm check in",
            "ar" to "من فضلك ارمش لتأكيد تسجيل الحضور",
            "ur" to "براہ کرم تصدیق کے لیے آنکھ جھپکائیں",
            "hi" to "कृपया पुष्टि के लिए पलक झपकाएं"
        ),
        "override" to mapOf(
            "en" to "Unable to verify. Supervisor override allowed",
            "ar" to "تعذر التحقق. يسمح بتجاوز المشرف",
            "ur" to "تصدیق ممکن نہیں۔ سپروائزر کی اجازت دی گئی ہے",
            "hi" to "सत्यापन संभव नहीं है। पर्यवेक्षक अनुमति दी गई है"
        )
    )

    fun get(key: String, lang: String): String {
        return table[key]?.get(lang)
            ?: table[key]?.get("en")
            ?: ""
    }
}
