# 📖 Handleiding voor Beheerders & Familie (v0.8.5)

**🌐 Taal:** [English](DOCS_ADMIN.md) · [Русский](DOCS_ADMIN_RU.md) · **Nederlands**

Welkom bij de Senioren Launcher. Deze handleiding is bedoeld voor familieleden, mantelzorgers en beheerders. De launcher is volledig voorbereid op **Android 16 (Baklava)** en voldoet aan de strenge Europese privacywetgeving (**AVG/GDPR**).

---

## 🔐 1. Autorisatie: Jezelf instellen als Beheerder
Om de telefoon op afstand te kunnen beheren, moet uw nummer herkend worden als een geautoriseerd SOS-contact.

### Stappenplan voor installatie:
1.  **Open Contacten:** Tik op het scherm van de senior op de knop **"Contacten"**.
2.  **Contact toevoegen:** Voeg uzelf toe (of bewerk uw bestaande contact).
3.  **Ster-markering:** Zorg dat u bent gemarkeerd als een **Favoriet/SOS contact** (meestal door op het sterretje bij de naam te tikken). 
4.  **Nummerherkenning:** Voer uw nummer in. De app herkent alle formaten automatisch:
    *   Lokaal: `0487123456`
    *   Internationaal: `+32487123456` of `0032487123456`
5.  **Testen:** Stuur een SMS met de tekst `#PING` vanaf uw telefoon naar de senior. Als u een bevestiging ziet op hun scherm, is de koppeling geslaagd.

---

## 🔑 Pincode van de instellingen
- **Standaard pincode: `1234`.** In de installatiewizard (stappen voor de mantelzorger) kunt u een eigen pincode kiezen. Tot die tijd werkt `1234`.
- **Vergrendelen:** het menu vraagt pas om de pincode nadat u onderaan *Instellingen* op **"Menu vergrendelen"** tikt.
- **Op afstand wijzigen:** stuur vanaf een SOS-contact een SMS `#PIN 5678` (zie [COMMANDS_NL.md](COMMANDS_NL.md)).
- **Pincode vergeten:** stuur `#PIN` met een nieuwe code vanaf een SOS-contact, of installeer de app opnieuw (alle instellingen worden gewist).

### 💬 Telegram (berichten en bellen)
Telegram zit in de launcher ingebouwd, zodat de senior de Telegram-app niet hoeft te leren. Telegram-chats staan in **Berichten** naast de sms-gesprekken, en Telegram-gesprekken gebruiken het eigen grote belscherm van de launcher.

**Koppelen (eenmalig):**
1. Open **Berichten** → **"Telegram koppelen"**.
2. Controleer het telefoonnummer en verstuur de code. De code komt per sms en wordt **automatisch ingevuld**. Stuurt Telegram de code naar een ander apparaat waar de senior al is ingelogd, typ hem dan zelf in.
3. Heeft het account een Telegram-wachtwoord (verificatie in twee stappen), vul het in.
- **Makkelijker met een helper:** tik op **"Laat een helper een code scannen"**. Open op je eigen telefoon Telegram → *Instellingen* → *Apparaten* → **Desktopapparaat koppelen** en scan de code op de telefoon van de senior.
- Het nummer moet al een Telegram-account hebben. Zo niet, maak dat eerst aan in de Telegram-app.
- Ontbreekt de knop, dan is deze versie van de app zonder Telegram gebouwd (zie [TELEGRAM.md](TELEGRAM.md)).

**Bellen:**
- Open een Telegram-chat en tik bovenaan op de grote groene knop **"Bellen"**, of op de blauwe **camera**-knop ernaast voor een **videogesprek**. De eerste keer vraagt Android om de microfoon (en camera): sta die toe, anders kan de ander de senior niet horen of zien.
- Tijdens het gesprek: luidspreker, microfoon (dempen), **camera aanzetten** en ophangen. Het scherm gaat uit als de telefoon tegen het oor wordt gehouden.
- **Videogesprekken:** de ander vult het scherm; het eigen beeld staat klein in een hoek en kan met de vinger worden verschoven. Knoppen: camera aan/uit, **andere camera** (voor/achter), luidspreker, microfoon, ophangen. Het knoppenpaneel klapt in met het handvat erboven (of door op het beeld te tikken); **Ophangen** blijft altijd zichtbaar. Videogesprekken beginnen op de luidspreker.
- **Inkomende Telegram-oproepen** gaan over met de gewone beltoon en openen een schermvullend scherm met **Opnemen / Weigeren**, ook op het vergrendelscherm.
- Telegram versleutelt gesprekken end-to-end.

**Telegram voor gewone oproepen:** *Instellingen* → **Bellen**: **"Bel buitenlandse nummers via Telegram"** (standaard aan) en **"Bel altijd via Telegram"**. Belt de senior een nummer vanuit het Telefoon-scherm, dan gaat dat via Telegram (in de launcher) als dat contact Telegram heeft; zo niet, dan wordt de officiële Telegram-app geprobeerd en anders wordt er gewoon gebeld. Alarmnummers en andere korte nummers gaan altijd via het telefoonnetwerk.

**Geen simkaart:** oproepen vanuit het Telefoon-scherm gaan automatisch via Telegram. Heeft het contact geen Telegram, dan meldt de telefoon dat bellen niet lukt. Alarmnummers (112/911) werken ook zonder simkaart.

**Nog niet ondersteund:** foto's, spraakberichten en bestanden verschijnen als tekstregel "[Foto of bestand]"; groepschats worden nog niet getoond.

**Ontkoppelen:** Berichten → Telegram → **"Telegram loskoppelen"**. Dit logt uit en wist de Telegram-gegevens op de telefoon.

### 🕐 Grote klok
- Tik op de tijd bovenaan het startscherm om tijd en datum heel groot te zien. Tik ergens om te sluiten.

### 🆘 SOS: zorgen dat hulp komt
Als de senior op SOS drukt (of een val wordt gedetecteerd):
1. **Berichten:** elk SOS-contact krijgt een sms met de locatie. Contacten die de sms niet bereikt (geen simkaart, of versturen mislukt) krijgen hetzelfde bericht via **Telegram**. Op het scherm staat "Noodsignaal verstuurd: X per sms, Y via Telegram".
2. **Bellen:** de telefoon belt de SOS-contacten om de beurt, op de luidspreker, tot iemand ongeveer 25 seconden met de senior praat (hooguit 3 rondes). Zonder simkaart zijn dat Telegram-oproepen. **"Ik ben veilig"** stopt het bellen.
3. **Stille modus telt niet:** tijdens een noodgeval zet de telefoon stille modus en Niet storen uit en het volume van beltoon, gesprek en wekker op maximaal, zodat het terugbellen gehoord wordt. De oude instellingen komen terug na **"Ik ben veilig"** of na 30 minuten. Dat gebeurt ook als de senior een alarmnummer belt.
4. **Komt er niets door** (geen simkaart, geen internet, geen SOS-contacten…), dan zegt een rood scherm **"HULP KOMT NIET"**, met de reden en een grote knop om **112/911** te bellen, wat ook zonder simkaart werkt.

**Voor de SOS-contacten (beheerders): zorg dat de senior doorkomt als uw telefoon op stil staat.**
- **iPhone:** Contacten → de senior → Wijzig → Beltoon → zet **Noodgevallen omzeilen** aan (ook bij Berichttoon).
- **Android:** markeer het contact als favoriet en sta in Niet storen oproepen en berichten van **favorieten** toe, en zet **herhaalde bellers** aan. SOS belt binnen enkele minuten opnieuw, zodat "herhaalde bellers" het laat overgaan.
- **Alarm voor beheerders:** installeer Senioren Launcher ook op uw eigen telefoon (u hoeft hem niet als startscherm te gebruiken; open hem één keer en sta sms toe). Elk SOS-bericht begint met 🆘; komt er een binnen, dan geeft uw telefoon een **luid alarm**, ook op stil, met **Terugbellen**, **Kaart openen** en **Alarm stoppen**.

**Benodigde toestemmingen** (de wizard vraagt de meeste): toegang tot Niet storen (*Instellingen → Apps → Speciale app-toegang → Toegang tot Niet storen → Senioren Launcher*), toegang tot meldingen, en op **Xiaomi/Redmi/POCO**-telefoons **Autostart** voor de launcher (*Instellingen → Apps → Apps beheren → Senioren Launcher → Autostart*). Zonder Autostart blokkeert MIUI de meldingendienst: dan werken "Alles wissen" en het verbergen van de belmelding van de Telegram-app niet.

### 🔔 Meldingen en de terugknop
- **"Alles wissen"** in het meldingenscherm markeert chats in Telegram, WhatsApp en vergelijkbare apps ook als gelezen, zodat de meldingen niet meteen terugkomen.
- De **terugpijl** bovenaan elk scherm gaat direct naar het startscherm.

### 🔄 Telefoon overdragen aan een nieuwe eigenaar
- *Instellingen* → Systeem → **"Installatie opnieuw starten na herstart"**. Na de volgende herstart begint de installatiewizard opnieuw (één keer). Het opgeslagen eigen nummer wordt gewist; contacten, medicijnen en wekkers blijven. Ontkoppel Telegram zo nodig zelf in Berichten.

---

## 📱 2. Privacy & Veiligheid (Android 16 Ready)
De app gebruikt **Privacy by Design**. Dit betekent:
*   **Privacy Labels:** Op schermen waar gevoelige data wordt gebruikt (zoals Locatie of Contacten), ziet de senior nu expliciet een blauw privacy-icoon met uitleg waarom deze data nodig is.
*   **Veilige Updates:** de launcher kijkt één keer per dag of er een nieuwe versie is. Zo ja, dan verschijnt de melding **"Nieuwe versie … beschikbaar"** met een korte lijst van wat er nieuw is. Er wordt pas iets gedownload als je erop tikt; dan komt de update via HTTPS binnen en vraagt Android om te installeren.
*   **Scambescherming:** De launcher blokkeert automatisch bekende spam-nummers en waarschuwt bij verdachte SMS-berichten.

---

## 🎮 3. Afstandsbediening via SMS (Volledige Lijst)
Stuur een SMS naar de senior met een van de volgende codes. De app reageert direct.

| Categorie | Code | Actie |
|:----------|:-----|:------|
| **Welzijn** | `#WAAR` | Ontvang de GPS-locatie via Google Maps. |
| | `#STATUS` | Check batterij, signaalsterkte en volume. |
| | `#PING` | Toon "ALLES GOED?" groot op het scherm. |
| | `#BEL_TERUG` | De telefoon belt u onmiddellijk terug. |
| **Hulp** | `#LAMP AAN` / `#LAMP UIT` | Schakel de zaklamp op afstand. |
| | `#KNIPPER` | Laat de zaklamp 10x knipperen (om de telefoon te vinden). |
| | `#ALARM [tekst]` | Activeer een luid alarm met een boodschap. |
| **Instellingen** | `#VOLUME [1-15]` | Pas het belvolume aan (bijv. `#VOLUME 15`). |
| | `#HELDER [1-100]` | Pas de schermhelderheid aan. |
| | `#WIFI AAN` / `#WIFI UIT` | Beheer de internetverbinding. |
| | `#STIL AAN` / `#STIL UIT` | Schakel de stille modus (Niet Storen). |
| **Beheer** | `#PRIVACY` | Ontvang een overzicht van de privacy-instellingen. |
| | `#UPDATE_CHECK` | Forceer een controle op beschikbare app-updates. |
| | `#RESTART` | Herstart de launcher app. |
| | `#THEMA [1, 2 of 3]` | Wijzig thema (1=Klassiek, 2=Hoog Contrast, 3=Licht). |

---

## ❓ Veelgestelde Vragen (FAQ)

**V: Waarom reageert de telefoon niet op mijn SMS?**
*   Controleer of uw nummer met een sterretje (SOS) in de contactenlijst staat.
*   Zorg dat de senior beltegoed heeft voor het versturen van de antwoord-SMS.

**V: Kan ik meerdere beheerders instellen?**
*Ja, elk contact dat gemarkeerd is als SOS/Favoriet kan de commando's gebruiken.*

**V: Is de locatiebepaling nauwkeurig?**
*De app gebruikt GPS. Als de senior binnen is, kan de nauwkeurigheid variëren. De SMS bevat altijd de geschatte afwijking in meters.*

---

## 📬 Contact voor Beheerders
Voor technische ondersteuning bij het instellen van meerdere toestellen of specifieke Android 16 vragen: [amine.chtaiti@gmail.com](mailto:amine.chtaiti@gmail.com).
