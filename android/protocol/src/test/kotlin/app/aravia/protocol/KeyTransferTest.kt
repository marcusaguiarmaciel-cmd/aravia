package app.aravia.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The QR below was sealed by Python's `cryptography` (AESGCM, hashlib.pbkdf2_hmac) with code 482913. */
class KeyTransferTest {
    private val qr = "ARAVIA-KEY1:AAECAwQFBgcICQoLDA0OD2RlZmdoaWprbG1ubyp8enUYBp1roOC4b2ZEYMh2gngIJFWYzBw6L28u0Hwxon_Lg9QAa5f2HMEN-h2XoV_nR9TndkpbrWsaZIYiOjOrOw7b9Ck1nC2YhBQ1sFdZFs66Kk6n6kqJ2aPy-KN_eSWR2lOnld5mbs6aJKrlVvRPSfuQHi_ZByWhUxcDsKWfAnHEaQov4X1G7qpckBLgSVtgRPH3jud84QCVDICA3BIj6rAx8m-kHdIrrYLEPyp4Ejzf"

    @Test
    fun opensWithTheCode() {
        val r = KeyTransfer.open(qr, "482913")
        assertTrue(r is KeyTransfer.Result.Opened)
        assertEquals("""{"v":1,"name":"Redmi Watch 5 Lite 5E6F","mac":"AA:BB:CC:DD:5E:6F","model":"M2410W1","keys":["000102030405060708090a0b0c0d0e0f","ffeeddccbbaa99887766554433221100"]}""",
            (r as KeyTransfer.Result.Opened).json)
    }

    @Test
    fun refusesAWrongCode() = assertTrue(KeyTransfer.open(qr, "482914") is KeyTransfer.Result.WrongCode)

    @Test
    fun refusesOtherText() = assertTrue(KeyTransfer.open("https://example.com", "482913") is KeyTransfer.Result.NotAravia)
}
