# Passing a band's key from Aravia on iPhone to Aravia on Android

Band › About › "Levar para o Android" shows a QR code and a six-digit code. Aravia on Android
scans the QR, asks for the code and stores the band with its keys. Nothing goes through a server.

## Format

```
QR text   = "ARAVIA-KEY1:" + base64url_no_padding(salt[16] ‖ nonce[12] ‖ ciphertext ‖ tag[16])
key       = PBKDF2-HMAC-SHA256(password = the six digits as ASCII, salt, 150 000 iterations, 32 bytes)
sealed    = AES-256-GCM(key, nonce, plaintext, additional data = "ARAVIA-KEY1")
plaintext = UTF-8 JSON {"v":1, "name":"<band name>", "mac":"<AA:BB:…>" | null, "model":"<code>",
                        "keys":["<32 hex digits>", …]}
```

`keys` lists every candidate key the iPhone holds for the band, the one that last logged in
first (`Vault.bandKeys`). The code is generated on the iPhone for each QR; a new code makes a new
salt and nonce.

## Why a code

The QR alone opens nothing: someone who photographs the screen still needs the six digits. The
stretch (150 000 rounds) makes trying all 10^6 codes slow, and the QR is only shown on request,
on the person's own screen. The key never reaches the account or Firebase.
