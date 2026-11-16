# Abhyas CBT — ऑफ़लाइन मॉक टेस्ट ऐप

कोचिंग की प्रश्न-पत्र PDF या JSON से Testbook जैसा CBT मॉक टेस्ट, विस्तृत सॉल्यूशन, टॉपिक-वार कमजोरी विश्लेषण और AI डाउट सॉल्वर। पूरा डेटा फ़ोन के IndexedDB में रहता है, ऐप बिना इंटरनेट चलता है (सिर्फ़ AI फ़ीचर को इंटरनेट चाहिए)।

- **Android APK:** [`dist/AbhyasCBT.apk`](dist/AbhyasCBT.apk)
- **PWA (ब्राउज़र में):** https://manishyadavji14-hash.github.io/abhyas-cbt/

## फ़ीचर

| क्षेत्र | क्या है |
|---|---|
| JSON Import | एक या कई `.json` फ़ाइलें / पेस्ट किया हुआ AI जवाब। एक ही `exam_id` वाले हिस्से (Part 1, 2…) merge होते हैं। |
| Copy AI Extraction Prompt | तैयार प्रॉम्प्ट क्लिपबोर्ड पर; PDF के साथ ChatGPT/Claude/Gemini को दें → App Standard Schema JSON। |
| PDF → AI | PDF.js से पेज-दर-पेज: डबल-कॉलम क्रम, तिरछे/दोहराए गए वॉटरमार्क हटाना, विज्ञापन पेज छोड़ना, उत्तर कुंजी तालिका मिलाना, चित्रों को कैनवास से क्रॉप करना, AI से सॉल्यूशन + टॉपिक टैगिंग। बीच में रुकने पर वहीं से resume। API Key न हो तो ऑफ़लाइन regex पार्सर। |
| AI Providers | OpenAI-compatible (OpenAI, Kiro/OpenRouter/Groq/लोकल), Anthropic Claude, Google Gemini। APK में कॉल नेटिव ब्रिज से जाती हैं (CORS की समस्या नहीं)। |
| Rich content | KaTeX सूत्र (`$...$`, `$$...$$`), HTML टेबल, Base64 चित्र, इमोजी, द्विभाषी (हिंदी / English / दोनों), स्क्रॉल होने वाला गद्यांश बॉक्स। |
| CBT Player | प्री-टेस्ट स्क्रीन (समय, अंक, कस्टम नेगेटिव मार्किंग, भाषा), काउंटडाउन + प्रति-प्रश्न टाइमर, पॉज़ (बैकग्राउंड में अपने-आप), सेक्शन टैब, रंगीन प्रश्न पैलेट, Mark for Review & Next / Clear Response / Save & Next / Submit, स्वाइप नेविगेशन, बीच में छोड़कर Resume। |
| Analytics | स्कोरकार्ड, विषयवार टेबल (सबसे कमजोर विषय), टॉपिक: कमजोर (<50% या औसत से दोगुना समय) / मध्यम (50–75%) / मजबूत (>75%), Time Trap Detector, सभी टेस्ट का Overall Topic Analytics। |
| Weakness Practice | सिर्फ़ गलत / छोड़े / गलत+छोड़े / कमजोर टॉपिक / टाइम-ट्रैप प्रश्नों का तुरंत मिनी-टेस्ट। |
| Solutions | All / Correct / Incorrect / Unattempted / Bookmarked फ़िल्टर, आपका उत्तर, सही उत्तर, लिया समय, व्याख्या, Key Concept, **Ask AI / डाउट पूछें** चैट (हिस्ट्री सेव)। |
| Data | Practice sets, attempts, bookmarks, topic analytics, chats, settings — सब IndexedDB में। पूरा बैकअप/रिस्टोर (API Key बैकअप में नहीं जाती)। |

ऐप खुलते ही एक डेमो सेट (34 प्रश्न: हिंदी, अंग्रेज़ी पैसेज, संस्कृत गद्यांश, विज्ञान, गणित सूत्र, टेबल, चित्र, पर्यावरण, बाल विकास, GK, रीज़निंग, IT) मौजूद रहता है।

## JSON फ़ॉर्मेट (App Standard Schema)

```json
{
  "exam_meta": { "exam_id": "supertet_model_08", "title": "...", "duration_minutes": 150, "max_marks": 150,
                 "positive_marks": 1.0, "negative_marks": 0.0, "instructions_hi": "", "instructions_en": "" },
  "sections": ["HINDI", "ENGLISH", "..."],
  "passages": { "p_env_1": { "text_en": "...", "text_hi": "" } },
  "questions": [{
    "q_no": 1, "section": "HINDI", "topic": "कारक एवं विभक्ति", "syllabus_tag": "हिंदी व्याकरण - कारक",
    "difficulty": "Easy", "passage_id": null,
    "question_hi": "...", "question_en": "", "question_media": { "image_base64": null, "table_html": null },
    "options": [{ "id": "A", "text_hi": "करण", "text_en": "", "image_base64": null }, "..."],
    "correct_option": "D", "solution_hi": "...", "solution_en": "", "key_concept": "..."
  }]
}
```

Importer ढीले फ़ॉर्मेट भी स्वीकार करता है: `options` को string array या `{A: ..}` object, `answer: "b"` / `"(2)"` / `"ब"`, सिर्फ़ `question` फ़ील्ड (हिंदी/अंग्रेज़ी अपने-आप पहचानी जाती है)।

## प्रोजेक्ट संरचना

```
web/                 PWA (बिना build step के सीधे चलता है)
  index.html, sw.js, manifest.webmanifest, precache.js (generated)
  css/app.css        Tailwind से बना (styles/tailwind.css)
  js/                app.js (router), db.js (IndexedDB), ai.js, pdf-import.js, analytics.js,
                     schema.js, prompts.js, chat.js, components.js, util.js, ui.js, demo-set.js
  js/views/          home, import, set, pretest, exam, result, solutions, history, bookmarks, analytics, settings
  vendor/            KaTeX 0.16.22, PDF.js 4.10.38 (legacy build, cmaps, standard fonts)
android/             WebView wrapper (Java, कोई बाहरी dependency नहीं)
dist/AbhyasCBT.apk   बना हुआ APK
tools/               icons + precache generators
```

## Build

```bash
npm install
npm run build:icons     # icons + demo figure (only when the SVGs change)
npm run build           # Tailwind CSS + service-worker precache list
cd android && ./gradlew assembleRelease   # needs JDK 17–21 and Android SDK 35
```

APK एक committed keystore (`android/keystore/abhyas-shared.jks`) से साइन होता है, ताकि किसी भी मशीन पर बना नया APK पुराने के ऊपर अपडेट हो सके। यह ऐप Play Store के लिए नहीं है।

## सुरक्षा

- इम्पोर्ट की गई JSON/AI सामग्री whitelist sanitizer से गुज़रती है (script, event handlers, बाहरी इमेज, iframe हटते हैं; इमेज सिर्फ़ `data:image/...`)।
- Content-Security-Policy: सिर्फ़ ऐप की अपनी scripts चलती हैं।
- PDF.js 4.10 (CVE-2024-4367 पैच्ड) `isEvalSupported: false` के साथ।
- APK का WebView सिर्फ़ ऐप के assets लोड करता है; बाहरी लिंक ब्राउज़र में खुलते हैं।
