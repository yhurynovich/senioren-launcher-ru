# 📖 Guide for Caregivers & Family (v0.8.5)

**🌐 Language:** **English** · [Русский](DOCS_ADMIN_RU.md) · [Nederlands](DOCS_BEHEERDER_NL.md)

Welcome to the Senioren Launcher. This guide is meant for family members, caregivers and administrators. The launcher is fully prepared for **Android 16 (Baklava)** and complies with strict European privacy legislation (**GDPR**).

---

## 🛠️ 0. First installation (setup wizard)
1.  **Choose the language:** the very first screen asks for the language (Русский, Nederlands, ...). Russian is the default. You can change it later in *Settings*.
2.  **Accept the privacy statement.**
3.  **Choose a role:** *I am the caregiver* (technical setup) or *I am the user* (the senior's personal choices).
4.  **Caregiver steps:** permissions, system permissions, default apps (Home, Phone, SMS), main contacts (up to 4, they become favourites and SOS contacts), fall detection, PIN code and remote support.
5.  **Hand the phone to the senior,** who chooses the text size and the colours.

> **Tip:** every setup screen scrolls, and the *Next* button stays at the bottom of the screen, also at the largest text size.

---

## 🔐 1. Authorization: Setting yourself up as an Administrator
To manage the phone remotely, your number must be recognised as an authorised SOS contact.

### Step-by-step:
1.  **Open Contacts:** on the senior's screen, tap the **"Contacts"** button.
2.  **Add a contact:** add yourself (or edit your existing contact).
3.  **Star marking:** make sure you are marked as a **Favourite / SOS contact** (usually by tapping the star next to the name).
4.  **Number recognition:** enter your number. The app recognises all formats automatically:
    *   Local: `0487123456`
    *   International: `+32487123456` or `0032487123456`
5.  **Test:** send an SMS with the text `#PING` from your phone to the senior. If you see a confirmation on their screen, the link works.

---

## 🏠 2. Arranging the home screen
- **Layout and text size:** *Settings* (PIN protected) → choose 1x1, 2x3 or 3x4 and the text size. The number of tile rows adapts to the screen, so nothing is cut off. Extra tiles go to pages you can swipe through (page dots under the grid).
- **Changing the order of the tiles:** long-press a tile, then use **"◀ Earlier"** or **"Later ▶"** as often as you like and tap **DONE**. The dialog shows the position, for example "Calls: place 2 of 8".
- **Replacing or removing an app:** in the same dialog, tap **"Replace or remove"** (not available for Settings, Wi-Fi and Bluetooth).
- **Language:** *Settings* → Language. The app restarts and shows the new language.
- **Big clock:** tap the time at the top of the home screen to see the time and date in very large type. Tap anywhere to close it.

### 🔑 Settings PIN
- **Default PIN: `1234`.** You can choose a different PIN in the setup wizard (caregiver steps). Until then, `1234` works.
- **Locking:** the menu only asks for the PIN after you tap **"Lock menu"** at the bottom of *Settings*.
- **Changing it remotely:** send an SMS `#PIN 5678` from an SOS contact (see [COMMANDS.md](COMMANDS.md)).
- **Forgotten PIN:** send `#PIN` with a new code from an SOS contact, or reinstall the app (this clears all settings).

### 💬 Telegram (messages and calls)
The launcher has Telegram built in, so the senior doesn't need to learn the Telegram app. Telegram chats appear in **Messages** next to the SMS conversations, and Telegram calls use the launcher's own large call screen.

**Connecting (once):**
1. Open **Messages** → **"Connect Telegram"**.
2. Check the phone number and tap **"Send code"**. The code arrives by SMS and is **filled in automatically**. If Telegram sends the code to another device where the senior is already signed in, type it in.
3. If the account has a Telegram password (two-step verification), enter it.
- **Easier with a helper:** tap **"Let a helper scan a code"**. On your own phone, open Telegram → *Settings* → *Devices* → **Link Desktop Device** and scan the code on the senior's phone.
- The phone number must already have a Telegram account. If not, create one in the Telegram app first.
- If the button is missing, this build of the app was made without Telegram (see [TELEGRAM.md](TELEGRAM.md)).

**Calling:**
- Open a Telegram chat and tap the big green **"Call"** button at the top, or the blue **camera** button next to it for a **video call**. The first time, Android asks for the microphone (and camera): allow it, or the other person can't hear or see the senior.
- During a call: **Speaker**, **Microphone** (mute), **Turn on camera** and **Hang up**. The screen turns off when the phone is held to the ear.
- **Video calls:** the other person fills the screen; the senior's own picture is small in a corner and can be dragged anywhere. Buttons: **Camera on/off**, **Switch camera** (front/back), **Speaker**, **Microphone**, **Hang up**. The button panel folds away with the handle above it (or by tapping the video); **Hang up** always stays visible. Video calls start on the speaker.
- **Incoming Telegram calls** ring with the normal ringtone and open a full-screen **Answer / Decline** screen, also on the lock screen.
- Telegram encrypts calls end-to-end.

**Automatic Telegram for normal calls:** *Settings* → **Calls**: **"Prefer Telegram for international calls"** (on by default) and **"Prefer Telegram for all calls"**. When the senior calls a number from the Phone screen, it goes over Telegram (in the launcher) if that contact has Telegram; if not, the official Telegram app is tried, and otherwise a normal call is made. Emergency and other short numbers always go over the phone network.

**No SIM card:** calls from the Phone screen automatically go over Telegram. If the contact is not on Telegram, a message says the call can't be made. Emergency numbers (112/911) still work without a SIM.

**Not yet supported:** photos, voice messages and files show as "[Photo or file]"; group chats are not shown yet.

**Disconnecting:** Messages → Telegram → **"Disconnect Telegram"**. This signs out and deletes the Telegram data stored on the phone.

### 🆘 SOS: making sure help comes
When the senior presses SOS (or a fall is detected):
1. **Messages:** every SOS contact gets an SMS with the location. Contacts the SMS can't reach (no SIM card, or the SMS failed) get the same message over **Telegram**. The screen shows "Alarm sent: X by SMS, Y by Telegram".
2. **Calls:** the phone calls the SOS contacts one after another, on the speaker, until someone talks with the senior for about 25 seconds (at most 3 rounds). Without a SIM card these are Telegram calls. Tapping **"I'm safe"** stops it.
3. **Silent mode is ignored:** during an emergency the phone switches off silent mode and Do Not Disturb and sets ring, call and alarm volume to maximum, so the call-back is heard. The old settings come back after **"I'm safe"** or after 30 minutes. This also happens when the senior dials an emergency number.
4. **If nothing got through** (no SIM, no internet, no SOS contacts…), a red screen says **"HELP IS NOT COMING"** with the reason and a big button to call **112/911**, which works even without a SIM card.

**For the SOS contacts (caregivers): make sure the senior gets through when your phone is on silent.**
- **iPhone:** Contacts → the senior → Edit → Ringtone → turn on **Emergency Bypass** (do the same for Text Tone).
- **Android:** star the senior's contact, then in Do Not Disturb allow calls and messages from **starred contacts** and turn on **repeat callers**. The SOS calls you again within minutes, so "repeat callers" lets it ring through.
- **Caregiver alarm:** install Senioren Launcher on your own phone too (you don't have to use it as your home screen; just open it once and allow SMS). Every SOS message starts with 🆘; when one arrives, your phone sounds a **loud alarm**, even on silent, with **Call back**, **Open map** and **Stop alarm**.

**Permissions for all of this** (the setup wizard asks for most): Do Not Disturb access (*Settings → Apps → Special app access → Do Not Disturb access → Senioren Launcher*), notification access, and on **Xiaomi/Redmi/POCO** phones **Autostart** for the launcher (*Settings → Apps → Manage apps → Senioren Launcher → Autostart*). Without Autostart, MIUI blocks the notification service: then "Clear all" and hiding the Telegram app's call banner don't work.

### 🔔 Notifications and the back button
- **"Clear all"** in the notifications screen also marks chats in Telegram, WhatsApp and similar apps as read, so they don't come back immediately.
- The **back arrow** at the top of every screen goes straight to the home screen.

### 🔄 Handing the phone to a new owner
- *Settings* → System → **"Restart setup after reboot"**. After the next reboot the setup wizard starts again (once). The saved own phone number is cleared; contacts, medication and alarms stay. Disconnect Telegram yourself in Messages if needed.

---

## 📱 3. Privacy & Safety (Android 16 Ready)
The app uses **Privacy by Design**. This means:
*   **Privacy Labels:** on screens where sensitive data is used (such as Location or Contacts), the senior now sees a blue privacy icon with an explicit explanation of why the data is needed.
*   **Secure Updates:** the launcher checks for a new version once a day. When there is one, a notification **"New version … available"** appears with a short "what's new". Nothing is downloaded until it is tapped; then the update downloads over HTTPS and Android asks to install it.
*   **Scam Protection:** the launcher automatically blocks known spam numbers and warns about suspicious SMS messages.

---

## 🎮 4. Remote Control via SMS (Full List)
Send an SMS to the senior with one of the following codes. The app reacts immediately. The codes are always Dutch, whatever language the app is set to (see [COMMANDS.md](COMMANDS.md) for the complete list with explanations).

| Category | Code | Action |
|:----------|:-----|:------|
| **Wellbeing** | `#WAAR` | Receive the GPS location via Google Maps. |
| | `#STATUS` | Check battery, signal strength and volume. |
| | `#PING` | Show "ALLES GOED?" (are you okay?) in large letters on the screen. |
| | `#BEL_TERUG` | The phone calls you back immediately. |
| **Help** | `#LAMP AAN` / `#LAMP UIT` | Switch the flashlight remotely. |
| | `#KNIPPER` | Make the flashlight blink 10 times (to find the phone). |
| | `#ALARM [text]` | Trigger a loud alarm with a message. |
| **Settings** | `#VOLUME [1-15]` | Adjust the ringtone volume (e.g. `#VOLUME 15`). |
| | `#HELDER [1-100]` | Adjust the screen brightness. |
| | `#WIFI AAN` / `#WIFI UIT` | Manage the internet connection. |
| | `#STIL AAN` / `#STIL UIT` | Switch silent mode (Do Not Disturb). |
| **Management** | `#PRIVACY` | Receive an overview of the privacy settings. |
| | `#UPDATE_CHECK` | Force a check for available app updates. |
| | `#RESTART` | Restart the launcher app. |
| | `#THEMA [1, 2 or 3]` | Change the theme (1=Classic, 2=High Contrast, 3=Light). |

---

## ❓ Frequently Asked Questions (FAQ)

**Q: Why does the phone not respond to my SMS?**
*   Check that your number is in the contact list with a star (SOS).
*   Make sure the senior has credit to send the reply SMS.

**Q: Can I set up multiple administrators?**
*Yes, every contact marked as SOS/Favourite can use the commands.*

**Q: Is the location accurate?**
*The app uses GPS. If the senior is indoors, the accuracy may vary. The SMS always contains the estimated deviation in metres.*

**Q: The text is too large and I cannot find a button.**
*Screens scroll, and the home screen shows fewer rows when the text is larger; swipe sideways for more tiles. If the text is still too large, lower the text size in Settings or send `#LETTER 3`.*

---

## 📬 Contact for Administrators
For technical support when setting up multiple devices or specific Android 16 questions: [amine.chtaiti@gmail.com](mailto:amine.chtaiti@gmail.com).
