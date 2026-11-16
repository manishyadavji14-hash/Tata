// Pre-loaded demo practice set so the app can be tried immediately after install.
import { FIG_TRIANGLE } from './demo-figures.js';

const o = (A, B, C, D) => [A, B, C, D].map((t, i) => {
  const id = 'ABCD'[i];
  if (Array.isArray(t)) return { id, text_hi: t[0], text_en: t[1], image_base64: null };
  return { id, text_hi: /[\u0900-\u097F]/.test(t) ? t : '', text_en: /[\u0900-\u097F]/.test(t) ? '' : t, image_base64: null };
});
const m = (A, B, C, D) => [A, B, C, D].map((t, i) => ({ id: 'ABCD'[i], text_hi: t, text_en: t, image_base64: null }));
const noMedia = { image_base64: null, table_html: null };

export const DEMO_SET = {
  exam_meta: {
    exam_id: 'demo_supertet_mini_01',
    title: 'DEMO • SUPER TET प्राथमिक मिनी मॉक टेस्ट-01',
    duration_minutes: 40,
    max_marks: 34,
    positive_marks: 1.0,
    negative_marks: 0.0,
    instructions_hi: 'यह डेमो सेट है। सभी प्रश्नों के अंक समान हैं। गलत उत्तर पर कोई ऋणात्मक अंक नहीं है (प्री-टेस्ट स्क्रीन पर बदल सकते हैं)।',
    instructions_en: 'This is a demo set. All questions carry equal marks. No negative marking (can be changed on the pre-test screen).',
  },
  sections: ['HINDI', 'ENGLISH', 'SANSKRIT', 'SCIENCE', 'MATHS', 'पर्यावरण एवं सामाजिक अध्ययन', 'बाल विकास (CDP)', 'सामान्य ज्ञान एवं समसायिक घटनाएं', 'REASONING', 'सूचना तकनीक'],
  passages: {
    p_eng_1: {
      title: 'Read the passage and answer the questions that follow.',
      text_en: 'The environment is everything around us. It includes living things such as plants, animals and human beings, and non-living things such as air, water, soil and sunlight. All of them depend on one another. When we cut forests, waste water or burn too much fuel, we <b>deplete</b> the natural resources that support life on Earth. Planting trees, saving water and reducing pollution are simple steps that every child can take to protect the environment for the future.',
      text_hi: '',
    },
    p_skt_1: {
      title: 'अधोलिखितं गद्यांशं पठित्वा प्रश्नानाम् उत्तराणि लिखत।',
      text_hi: 'अस्माकं देशः भारतवर्षः अस्ति। अस्य उत्तरस्यां दिशि हिमालयः पर्वतः अस्ति। दक्षिणस्यां दिशि हिन्दमहासागरः अस्ति। गङ्गा यमुना च अत्र प्रमुखे नद्यौ स्तः। भारतीयाः सर्वेषु धर्मेषु आदरं कुर्वन्ति।',
      text_en: '',
    },
  },
  questions: [
    // ---------------- HINDI ----------------
    {
      q_no: 1, section: 'HINDI', topic: 'कारक एवं विभक्ति', syllabus_tag: 'हिंदी व्याकरण - कारक', difficulty: 'Easy', passage_id: null,
      question_hi: '"चारपाई पर राम बैठा है।" इस वाक्य में कारक बताइए-', question_en: '', question_media: noMedia,
      options: o('करण', 'सम्प्रदान', 'संबंध', 'अधिकरण'), correct_option: 'D',
      solution_hi: "वाक्य में 'चारपाई पर' क्रिया (बैठना) का आधार बता रहा है। क्रिया के आधार को बताने वाला कारक <b>अधिकरण</b> कहलाता है और इसके चिह्न 'में, पर' होते हैं।", solution_en: '',
      key_concept: "अधिकरण कारक के चिह्न 'में' और 'पर' होते हैं।",
    },
    {
      q_no: 2, section: 'HINDI', topic: 'संधि', syllabus_tag: 'हिंदी व्याकरण - स्वर संधि', difficulty: 'Easy', passage_id: null,
      question_hi: "'विद्यालय' शब्द का सही संधि-विच्छेद है-", question_en: '', question_media: noMedia,
      options: o('विद्या + आलय', 'विद्य + आलय', 'विद्या + लय', 'विद्यः + आलय'), correct_option: 'A',
      solution_hi: "विद्या (आ) + आलय (आ) → आ + आ = आ। यह <b>दीर्घ स्वर संधि</b> है: सजातीय दीर्घ स्वर मिलकर दीर्घ स्वर बनाते हैं। अतः विद्या + आलय = विद्यालय।", solution_en: '',
      key_concept: 'दीर्घ संधि: अ/आ + अ/आ = आ',
    },
    {
      q_no: 3, section: 'HINDI', topic: 'अलंकार', syllabus_tag: 'हिंदी व्याकरण - अर्थालंकार', difficulty: 'Medium', passage_id: null,
      question_hi: '"चरण-कमल बंदौं हरि राई।" पंक्ति में कौन-सा अलंकार है?', question_en: '', question_media: noMedia,
      options: o('उपमा', 'रूपक', 'उत्प्रेक्षा', 'अनुप्रास'), correct_option: 'B',
      solution_hi: "यहाँ 'चरण' (उपमेय) और 'कमल' (उपमान) में कोई भेद नहीं किया गया, चरण को ही कमल मान लिया गया है। जहाँ उपमेय पर उपमान का <b>अभेद आरोप</b> हो, वहाँ <b>रूपक अलंकार</b> होता है। (उपमा में 'सा, सी, सम' जैसे वाचक शब्द आते हैं, जो यहाँ नहीं हैं।)", solution_en: '',
      key_concept: 'उपमेय = उपमान (अभेद) → रूपक; वाचक शब्द हों → उपमा',
    },
    {
      q_no: 4, section: 'HINDI', topic: 'पर्यायवाची शब्द', syllabus_tag: 'हिंदी शब्द-भंडार - पर्यायवाची', difficulty: 'Easy', passage_id: null,
      question_hi: "निम्नलिखित में से कौन-सा शब्द 'सूर्य' का पर्यायवाची <u>नहीं</u> है?", question_en: '', question_media: noMedia,
      options: o('दिनकर', 'भास्कर', 'सुधाकर', 'रवि'), correct_option: 'C',
      solution_hi: "दिनकर, भास्कर, रवि — तीनों सूर्य के पर्यायवाची हैं। <b>सुधाकर</b> (सुधा = अमृत + आकर = खान) 'चंद्रमा' का पर्यायवाची है।", solution_en: '',
      key_concept: 'सुधाकर, शशि, राकेश, हिमांशु = चंद्रमा',
    },
    // ---------------- ENGLISH ----------------
    {
      q_no: 5, section: 'ENGLISH', topic: 'Reading Comprehension', syllabus_tag: 'English - Unseen Passage', difficulty: 'Easy', passage_id: 'p_eng_1',
      question_hi: '', question_en: 'According to the passage, the environment includes:', question_media: noMedia,
      options: o('Only living things', 'Only non-living things', 'Both living and non-living things', 'Only human beings'), correct_option: 'C',
      solution_hi: 'गद्यांश के अनुसार पर्यावरण में सजीव (पौधे, जानवर, मनुष्य) और निर्जीव (हवा, पानी, मिट्टी, धूप) दोनों शामिल हैं।',
      solution_en: 'The passage clearly says the environment includes living things (plants, animals, human beings) and non-living things (air, water, soil, sunlight).',
      key_concept: 'Locate the keyword "includes" in the passage.',
    },
    {
      q_no: 6, section: 'ENGLISH', topic: 'Vocabulary in Context', syllabus_tag: 'English - Unseen Passage', difficulty: 'Medium', passage_id: 'p_eng_1',
      question_hi: '', question_en: 'The word "<b>deplete</b>" as used in the passage means:', question_media: noMedia,
      options: o('to increase', 'to use up / reduce', 'to protect', 'to decorate'), correct_option: 'B',
      solution_hi: "'Deplete' का अर्थ है किसी चीज़ को खर्च करके कम कर देना। गद्यांश में जंगल काटने, पानी बर्बाद करने से संसाधन 'कम' होते हैं।",
      solution_en: '"Deplete" means to use up or reduce the quantity of something. Cutting forests and wasting water reduce natural resources.',
      key_concept: 'Deplete ↔ Replenish (antonym)',
    },
    {
      q_no: 7, section: 'ENGLISH', topic: 'Subject-Verb Agreement', syllabus_tag: 'English Grammar - Tense & Agreement', difficulty: 'Easy', passage_id: null,
      question_hi: '', question_en: 'Fill in the blank: "She ______ to school every day."', question_media: noMedia,
      options: o('go', 'goes', 'going', 'gone'), correct_option: 'B',
      solution_hi: "'Every day' आदत (habitual action) दिखाता है → Simple Present Tense. Subject 'She' (third person singular) है, इसलिए verb में -s/-es लगेगा: goes.",
      solution_en: '"Every day" shows a habit → simple present tense. With a third-person singular subject (she) the verb takes -s/-es: "goes".',
      key_concept: 'He/She/It/Singular noun + V1 + s/es',
    },
    {
      q_no: 8, section: 'ENGLISH', topic: 'Antonyms', syllabus_tag: 'English - Vocabulary', difficulty: 'Easy', passage_id: null,
      question_hi: '', question_en: 'Choose the antonym of "<b>Ancient</b>":', question_media: noMedia,
      options: o('Old', 'Modern', 'Antique', 'Archaic'), correct_option: 'B',
      solution_hi: "Ancient = प्राचीन। इसका विलोम Modern (आधुनिक) है। Old, Antique, Archaic — ये सभी 'पुराने' के समानार्थी हैं।",
      solution_en: 'Ancient means very old. Its opposite is Modern. Old, antique and archaic are synonyms.',
      key_concept: 'Eliminate synonyms first, the odd one is the antonym.',
    },
    // ---------------- SANSKRIT ----------------
    {
      q_no: 9, section: 'SANSKRIT', topic: 'विभक्ति एवं कारक', syllabus_tag: 'संस्कृत व्याकरण - कारक', difficulty: 'Easy', passage_id: null,
      question_hi: "'रामः वनं गच्छति।' — इस वाक्य में 'वनम्' पद में कौन-सी विभक्ति है?", question_en: '', question_media: noMedia,
      options: o('प्रथमा', 'द्वितीया', 'तृतीया', 'सप्तमी'), correct_option: 'B',
      solution_hi: "'गच्छति' (जाता है) क्रिया का फल 'वन' पर पड़ रहा है, अतः 'वन' कर्म है। <b>कर्मणि द्वितीया</b> सूत्र से कर्म कारक में द्वितीया विभक्ति होती है — वनम्।", solution_en: '',
      key_concept: 'कर्मणि द्वितीया',
    },
    {
      q_no: 10, section: 'SANSKRIT', topic: 'धातु रूप', syllabus_tag: 'संस्कृत व्याकरण - धातु एवं लकार', difficulty: 'Medium', passage_id: null,
      question_hi: "'गच्छति' पद किस धातु से बना है?", question_en: '', question_media: noMedia,
      options: o('गम्', 'गच्छ्', 'गत्', 'गा'), correct_option: 'A',
      solution_hi: "'गम्' (जाना) धातु को लट् लकार में 'गच्छ्' आदेश होता है: गम् → गच्छ् + अ + ति = <b>गच्छति</b> (लट् लकार, प्रथम पुरुष, एकवचन)।", solution_en: '',
      key_concept: 'गम् → गच्छ् (लट्, लोट्, लङ्, विधिलिङ् में)',
    },
    {
      q_no: 11, section: 'SANSKRIT', topic: 'अपठित गद्यांश', syllabus_tag: 'संस्कृत - गद्यांश', difficulty: 'Easy', passage_id: 'p_skt_1',
      question_hi: 'भारतस्य उत्तरस्यां दिशि कः अस्ति?', question_en: '', question_media: noMedia,
      options: o('समुद्रः', 'हिमालयः', 'विन्ध्याचलः', 'गङ्गा'), correct_option: 'B',
      solution_hi: "गद्यांश में लिखा है — 'अस्य उत्तरस्यां दिशि हिमालयः पर्वतः अस्ति।' अर्थात् भारत की उत्तर दिशा में हिमालय पर्वत है।", solution_en: '',
      key_concept: 'उत्तरस्यां दिशि = उत्तर दिशा में',
    },
    {
      q_no: 12, section: 'SANSKRIT', topic: 'वचन', syllabus_tag: 'संस्कृत - शब्द रूप', difficulty: 'Medium', passage_id: 'p_skt_1',
      question_hi: "गद्यांश में प्रयुक्त 'नद्यौ' पदे किं वचनम् अस्ति?", question_en: '', question_media: noMedia,
      options: o('एकवचनम्', 'द्विवचनम्', 'बहुवचनम्', 'न किमपि'), correct_option: 'B',
      solution_hi: "नदी (ईकारान्त स्त्रीलिंग) के प्रथमा विभक्ति रूप: नदी (एक), <b>नद्यौ</b> (द्वि), नद्यः (बहु)। 'गङ्गा यमुना च' — दो नदियाँ हैं, इसलिए द्विवचन; क्रिया भी 'स्तः' (द्विवचन) है।", solution_en: '',
      key_concept: 'नदी — नद्यौ — नद्यः',
    },
    // ---------------- SCIENCE ----------------
    {
      q_no: 13, section: 'SCIENCE', topic: 'मात्रक एवं विमाएँ', syllabus_tag: 'भौतिक विज्ञान - विमीय सूत्र', difficulty: 'Medium', passage_id: null,
      question_hi: 'सार्वत्रिक गुरुत्वाकर्षण नियतांक $G$ का विमीय सूत्र है-', question_en: 'The dimensional formula of the universal gravitational constant $G$ is:', question_media: noMedia,
      options: m('$[ML^{2}T^{-2}]$', '$[M^{-1}L^{3}T^{-2}]$', '$[MLT^{-2}]$', '$[M^{-1}L^{2}T^{-1}]$'), correct_option: 'B',
      solution_hi: '$F = \\dfrac{G m_1 m_2}{r^2} \\Rightarrow G = \\dfrac{F r^2}{m_1 m_2}$<br>$[G] = \\dfrac{[MLT^{-2}][L^2]}{[M^2]} = [M^{-1}L^{3}T^{-2}]$',
      solution_en: 'From $F = \\dfrac{G m_1 m_2}{r^2}$, $G = \\dfrac{F r^2}{m_1 m_2}$ so $[G] = [M^{-1}L^{3}T^{-2}]$.',
      key_concept: 'बल $F$ का विमीय सूत्र $[MLT^{-2}]$',
    },
    {
      q_no: 14, section: 'SCIENCE', topic: 'अम्ल, क्षार एवं लवण', syllabus_tag: 'रसायन विज्ञान - अम्ल', difficulty: 'Easy', passage_id: null,
      question_hi: 'सल्फ्यूरिक अम्ल का रासायनिक सूत्र क्या है? 🧪', question_en: 'What is the chemical formula of sulphuric acid? 🧪', question_media: noMedia,
      options: m('$HCl$', '$HNO_3$', '$H_2SO_4$', '$H_2CO_3$'), correct_option: 'C',
      solution_hi: '$HCl$ = हाइड्रोक्लोरिक अम्ल, $HNO_3$ = नाइट्रिक अम्ल, $H_2SO_4$ = <b>सल्फ्यूरिक अम्ल</b> (रसायनों का राजा), $H_2CO_3$ = कार्बोनिक अम्ल।',
      solution_en: '$H_2SO_4$ is sulphuric acid, known as the "king of chemicals".',
      key_concept: 'सल्फ्यूरिक अम्ल = $H_2SO_4$ (King of Chemicals)',
    },
    {
      q_no: 15, section: 'SCIENCE', topic: 'विटामिन एवं पोषण', syllabus_tag: 'जीव विज्ञान - पोषण', difficulty: 'Easy', passage_id: null,
      question_hi: 'स्कर्वी रोग किस विटामिन की कमी से होता है?', question_en: 'Scurvy is caused by the deficiency of which vitamin?', question_media: noMedia,
      options: o(['विटामिन A', 'Vitamin A'], ['विटामिन B₁', 'Vitamin B₁'], ['विटामिन C', 'Vitamin C'], ['विटामिन D', 'Vitamin D']), correct_option: 'C',
      solution_hi: 'विटामिन C (एस्कॉर्बिक अम्ल) की कमी से <b>स्कर्वी</b> (मसूड़ों से खून आना) होता है। A → रतौंधी, B₁ → बेरी-बेरी, D → रिकेट्स।',
      solution_en: 'Vitamin C (ascorbic acid) deficiency causes scurvy (bleeding gums). A → night blindness, B₁ → beri-beri, D → rickets.',
      key_concept: 'A-रतौंधी, B₁-बेरीबेरी, C-स्कर्वी, D-रिकेट्स, K-रक्त का थक्का न जमना',
    },
    // ---------------- MATHS ----------------
    {
      q_no: 16, section: 'MATHS', topic: 'द्विघात समीकरण', syllabus_tag: 'गणित - बीजगणित', difficulty: 'Medium', passage_id: null,
      question_hi: 'यदि द्विघात समीकरण $2x^2-8x+m=0$ का एक मूल $\\frac{5}{2}$ है, तो $m$ का मान है-', question_en: 'If one root of the quadratic equation $2x^2-8x+m=0$ is $\\frac{5}{2}$, then the value of $m$ is:', question_media: noMedia,
      options: m('$\\frac{15}{2}$', '$5$', '$10$', '$-\\frac{15}{2}$'), correct_option: 'A',
      solution_hi: 'मूल समीकरण को संतुष्ट करता है, अतः $x=\\frac{5}{2}$ रखने पर:<br>$2\\left(\\frac{25}{4}\\right) - 8\\left(\\frac{5}{2}\\right) + m = 0$<br>$\\frac{25}{2} - 20 + m = 0 \\Rightarrow m = 20 - \\frac{25}{2} = \\frac{15}{2}$',
      solution_en: 'Substitute $x=\\frac{5}{2}$: $\\frac{25}{2} - 20 + m = 0 \\Rightarrow m = \\frac{15}{2} = 7.5$',
      key_concept: 'मूल रखने पर समीकरण का मान शून्य होता है।',
    },
    {
      q_no: 17, section: 'MATHS', topic: 'घातांक एवं विभाज्यता', syllabus_tag: 'गणित - संख्या पद्धति', difficulty: 'Medium', passage_id: null,
      question_hi: '$(3^{31}+3^{32}+3^{33}+3^{34})$ निम्न में से किससे पूर्णतः विभाज्य है?', question_en: '$(3^{31}+3^{32}+3^{33}+3^{34})$ is exactly divisible by:', question_media: noMedia,
      options: m('$11$', '$10$', '$13$', '$17$'), correct_option: 'B',
      solution_hi: 'सबसे छोटी घात $3^{31}$ बाहर निकालें:<br>$3^{31}(1+3+3^2+3^3) = 3^{31}(1+3+9+27) = 3^{31}\\times 40$<br>$40 = 10\\times 4$, अतः व्यंजक <b>10</b> से पूर्णतः विभाज्य है।',
      solution_en: '$3^{31}(1+3+9+27) = 3^{31}\\times 40$, which is divisible by $10$.',
      key_concept: 'सबसे छोटी घात को उभयनिष्ठ (common) लें।',
    },
    {
      q_no: 18, section: 'MATHS', topic: 'घड़ी', syllabus_tag: 'गणित - घड़ी एवं कैलेंडर', difficulty: 'Hard', passage_id: null,
      question_hi: '3 और 4 बजे के बीच किस समय घड़ी की दोनों सुइयाँ एक-दूसरे के विपरीत (180°) होंगी? 🕒', question_en: 'At what time between 3 and 4 o\'clock will the hands of a clock be opposite to each other (180°)? 🕒', question_media: noMedia,
      options: m('$3\\text{ बजकर }49\\frac{1}{11}\\text{ मिनट}$', '$3\\text{ बजकर }16\\frac{4}{11}\\text{ मिनट}$', '$3\\text{ बजकर }32\\frac{8}{11}\\text{ मिनट}$', '$3\\text{ बजकर }45\\text{ मिनट}$'), correct_option: 'A',
      solution_hi: '$H$ बजे के बाद सुइयाँ विपरीत होने का सूत्र: $t = \\frac{12}{11}(5H + 30)$ मिनट<br>$t = \\frac{12}{11}(15 + 30) = \\frac{12 \\times 45}{11} = \\frac{540}{11} = 49\\frac{1}{11}$ मिनट<br>अतः समय $= 3\\text{ बजकर }49\\frac{1}{11}\\text{ मिनट}$',
      solution_en: 'Opposite hands after H o\'clock: $t = \\frac{12}{11}(5H+30) = \\frac{540}{11} = 49\\frac{1}{11}$ minutes past 3.',
      key_concept: 'मिलना: $\\frac{12}{11}(5H)$; विपरीत: $\\frac{12}{11}(5H\\pm30)$; समकोण: $\\frac{12}{11}(5H\\pm15)$',
    },
    {
      q_no: 19, section: 'MATHS', topic: 'पाइथागोरस प्रमेय', syllabus_tag: 'गणित - ज्यामिति', difficulty: 'Easy', passage_id: null,
      question_hi: 'दी गई आकृति में $\\triangle ABC$ का $\\angle B = 90^\\circ$, $AB = 6$ सेमी तथा $BC = 8$ सेमी है। कर्ण $AC$ की लंबाई ज्ञात कीजिए।', question_en: 'In the given figure, $\\angle B = 90^\\circ$ in $\\triangle ABC$, $AB = 6$ cm and $BC = 8$ cm. Find the hypotenuse $AC$.',
      question_media: { image_base64: FIG_TRIANGLE, table_html: null },
      options: m('$10$ cm', '$12$ cm', '$14$ cm', '$9$ cm'), correct_option: 'A',
      solution_hi: 'पाइथागोरस प्रमेय: $AC^2 = AB^2 + BC^2 = 6^2 + 8^2 = 36 + 64 = 100$<br>$AC = \\sqrt{100} = 10$ सेमी (त्रिक 6-8-10)',
      solution_en: '$AC^2 = 6^2 + 8^2 = 100 \\Rightarrow AC = 10$ cm.',
      key_concept: 'पाइथागोरस त्रिक: (3,4,5), (6,8,10), (5,12,13), (8,15,17)',
    },
    {
      q_no: 20, section: 'MATHS', topic: 'औसत', syllabus_tag: 'गणित - अंकगणित', difficulty: 'Easy', passage_id: null,
      question_hi: 'प्रथम 10 प्राकृत संख्याओं का औसत क्या है?', question_en: 'What is the average of the first 10 natural numbers?', question_media: noMedia,
      options: m('$5$', '$5.5$', '$6$', '$10$'), correct_option: 'B',
      solution_hi: 'प्रथम $n$ प्राकृत संख्याओं का औसत $= \\frac{n+1}{2} = \\frac{10+1}{2} = 5.5$',
      solution_en: 'Average of first $n$ natural numbers $= \\frac{n+1}{2} = 5.5$',
      key_concept: 'औसत $= \\frac{n+1}{2}$',
    },
    {
      q_no: 21, section: 'MATHS', topic: 'आँकड़ों का निर्वचन', syllabus_tag: 'गणित - सांख्यिकी', difficulty: 'Easy', passage_id: null,
      question_hi: 'नीचे दी गई तालिका एक विद्यालय की कक्षाओं में छात्रों की संख्या दर्शाती है। प्रति कक्षा छात्रों की औसत संख्या कितनी है?', question_en: 'The table shows the number of students in the classes of a school. What is the average number of students per class?',
      question_media: { image_base64: null, table_html: '<table><tr><th>कक्षा / Class</th><th>I</th><th>II</th><th>III</th><th>IV</th></tr><tr><td>छात्र / Students</td><td>40</td><td>35</td><td>45</td><td>30</td></tr></table>' },
      options: m('$35$', '$37.5$', '$40$', '$36.5$'), correct_option: 'B',
      solution_hi: 'कुल छात्र $= 40+35+45+30 = 150$<br>औसत $= \\frac{150}{4} = 37.5$',
      solution_en: 'Total $= 150$, average $= \\frac{150}{4} = 37.5$',
      key_concept: 'औसत = योग ÷ संख्या',
    },
    // ---------------- EVS / SOCIAL ----------------
    {
      q_no: 22, section: 'पर्यावरण एवं सामाजिक अध्ययन', topic: 'पर्यावरण आंदोलन', syllabus_tag: 'पर्यावरण - संरक्षण', difficulty: 'Easy', passage_id: null,
      question_hi: "'चिपको आंदोलन' मुख्य रूप से किससे संबंधित है? 🌳", question_en: "The 'Chipko Movement' is mainly related to: 🌳", question_media: noMedia,
      options: o(['जल संरक्षण', 'Water conservation'], ['वन संरक्षण', 'Forest conservation'], ['वन्यजीव संरक्षण', 'Wildlife conservation'], ['मृदा संरक्षण', 'Soil conservation']), correct_option: 'B',
      solution_hi: 'चिपको आंदोलन (1973, चमोली, उत्तराखंड) में लोग पेड़ों से चिपककर उन्हें कटने से बचाते थे। प्रमुख नेता: सुंदरलाल बहुगुणा, चंडी प्रसाद भट्ट, गौरा देवी। यह <b>वन संरक्षण</b> आंदोलन है।',
      solution_en: 'The Chipko movement (1973, Chamoli, Uttarakhand) — villagers hugged trees to stop felling. It is a forest conservation movement.',
      key_concept: 'चिपको (उत्तराखंड), अप्पिको (कर्नाटक) — वन संरक्षण',
    },
    {
      q_no: 23, section: 'पर्यावरण एवं सामाजिक अध्ययन', topic: 'वायुमंडल', syllabus_tag: 'भूगोल - वायुमंडल की परतें', difficulty: 'Medium', passage_id: null,
      question_hi: 'ओज़ोन परत वायुमंडल की किस परत में पाई जाती है?', question_en: 'In which layer of the atmosphere is the ozone layer found?', question_media: noMedia,
      options: o(['क्षोभमंडल', 'Troposphere'], ['समतापमंडल', 'Stratosphere'], ['मध्यमंडल', 'Mesosphere'], ['आयनमंडल', 'Ionosphere']), correct_option: 'B',
      solution_hi: 'ओज़ोन परत <b>समतापमंडल</b> (लगभग 15–35 किमी) में है और सूर्य की पराबैंगनी (UV) किरणों को सोखती है। क्रम: क्षोभ → समताप → मध्य → ताप (आयन) → बहिर्मंडल।',
      solution_en: 'The ozone layer lies in the stratosphere (≈15–35 km) and absorbs UV radiation.',
      key_concept: 'ओज़ोन = समतापमंडल; मौसम की घटनाएँ = क्षोभमंडल',
    },
    // ---------------- CDP ----------------
    {
      q_no: 24, section: 'बाल विकास (CDP)', topic: 'पियाजे का संज्ञानात्मक विकास', syllabus_tag: 'बाल विकास - संज्ञानात्मक सिद्धांत', difficulty: 'Medium', passage_id: null,
      question_hi: "जीन पियाजे के अनुसार बालक में 'वस्तु स्थायित्व' (Object Permanence) का विकास किस अवस्था में होता है?", question_en: "According to Jean Piaget, 'object permanence' develops in which stage?", question_media: noMedia,
      options: o(['संवेदी-गामक अवस्था', 'Sensorimotor stage'], ['पूर्व-संक्रियात्मक अवस्था', 'Pre-operational stage'], ['मूर्त संक्रियात्मक अवस्था', 'Concrete operational stage'], ['औपचारिक संक्रियात्मक अवस्था', 'Formal operational stage']), correct_option: 'A',
      solution_hi: 'पियाजे की अवस्थाएँ: संवेदी-गामक (0–2 वर्ष) → पूर्व-संक्रियात्मक (2–7) → मूर्त संक्रियात्मक (7–11) → औपचारिक संक्रियात्मक (11+)। <b>वस्तु स्थायित्व</b> (वस्तु आँखों से ओझल होने पर भी उसका अस्तित्व समझना) संवेदी-गामक अवस्था की प्रमुख उपलब्धि है।',
      solution_en: 'Object permanence is the key achievement of the sensorimotor stage (0–2 years).',
      key_concept: '0-2 संवेदी-गामक: वस्तु स्थायित्व; 2-7: जीववाद, आत्मकेंद्रितता; 7-11: संरक्षण',
    },
    {
      q_no: 25, section: 'बाल विकास (CDP)', topic: 'वाइगोत्स्की', syllabus_tag: 'बाल विकास - सामाजिक-सांस्कृतिक सिद्धांत', difficulty: 'Easy', passage_id: null,
      question_hi: "'समीपस्थ विकास का क्षेत्र' (ZPD) की अवधारणा किसने दी?", question_en: "Who gave the concept of 'Zone of Proximal Development' (ZPD)?", question_media: noMedia,
      options: o(['पियाजे', 'Piaget'], ['वाइगोत्स्की', 'Vygotsky'], ['कोहलबर्ग', 'Kohlberg'], ['स्किनर', 'Skinner']), correct_option: 'B',
      solution_hi: 'लेव वाइगोत्स्की ने ZPD दिया — बच्चा अकेले जो कर सकता है और किसी जानकार (MKO) की मदद से जो कर सकता है, उसके बीच का अंतर। इसी से <b>पाड़ (Scaffolding)</b> की अवधारणा जुड़ी है।',
      solution_en: 'Lev Vygotsky — ZPD is the gap between what a child can do alone and with help of a More Knowledgeable Other; linked with scaffolding.',
      key_concept: 'वाइगोत्स्की: ZPD, Scaffolding, MKO, Private speech',
    },
    {
      q_no: 26, section: 'बाल विकास (CDP)', topic: 'थॉर्नडाइक', syllabus_tag: 'बाल विकास - अधिगम के सिद्धांत', difficulty: 'Easy', passage_id: null,
      question_hi: "'प्रयास एवं त्रुटि' (Trial and Error) का सिद्धांत किसने प्रतिपादित किया?", question_en: "Who propounded the 'Trial and Error' theory of learning?", question_media: noMedia,
      options: o(['पावलव', 'Pavlov'], ['स्किनर', 'Skinner'], ['थॉर्नडाइक', 'Thorndike'], ['कोहलर', 'Köhler']), correct_option: 'C',
      solution_hi: 'ई. एल. थॉर्नडाइक ने भूखी बिल्ली पर प्रयोग (पज़ल बॉक्स) करके प्रयास एवं त्रुटि सिद्धांत दिया। अधिगम के नियम: तत्परता, अभ्यास, प्रभाव। (पावलव — शास्त्रीय अनुबंधन, स्किनर — क्रियाप्रसूत, कोहलर — सूझ)',
      solution_en: 'E. L. Thorndike (cat in a puzzle box). Laws: readiness, exercise, effect.',
      key_concept: 'थॉर्नडाइक-बिल्ली, पावलव-कुत्ता, स्किनर-चूहा/कबूतर, कोहलर-चिंपैंजी',
    },
    {
      q_no: 27, section: 'बाल विकास (CDP)', topic: 'RTE Act 2009', syllabus_tag: 'बाल विकास - शिक्षा का अधिकार अधिनियम', difficulty: 'Easy', passage_id: null,
      question_hi: 'निःशुल्क एवं अनिवार्य बाल शिक्षा का अधिकार अधिनियम (RTE Act) 2009 किस आयु वर्ग के बच्चों के लिए है?', question_en: 'The Right of Children to Free and Compulsory Education (RTE) Act 2009 applies to children of which age group?', question_media: noMedia,
      options: o(['3 से 14 वर्ष', '3 to 14 years'], ['6 से 14 वर्ष', '6 to 14 years'], ['6 से 18 वर्ष', '6 to 18 years'], ['5 से 15 वर्ष', '5 to 15 years']), correct_option: 'B',
      solution_hi: 'RTE Act 2009 (लागू: 1 अप्रैल 2010) — <b>6 से 14 वर्ष</b> के बच्चों को निःशुल्क एवं अनिवार्य शिक्षा; संविधान का अनुच्छेद 21(क) (86वाँ संशोधन, 2002)। धारा 17: शारीरिक दंड व मानसिक उत्पीड़न निषेध।',
      solution_en: 'RTE Act 2009 (in force from 1 April 2010) gives free and compulsory education to children aged 6–14 under Article 21A.',
      key_concept: 'अनुच्छेद 21A, 86वाँ संशोधन, 6-14 वर्ष, धारा 17 (दंड निषेध)',
    },
    // ---------------- GK ----------------
    {
      q_no: 28, section: 'सामान्य ज्ञान एवं समसायिक घटनाएं', topic: 'भारतीय संविधान', syllabus_tag: 'सामान्य ज्ञान - राजव्यवस्था', difficulty: 'Medium', passage_id: null,
      question_hi: 'भारतीय संविधान में मौलिक कर्तव्य किस संविधान संशोधन द्वारा जोड़े गए?', question_en: 'Fundamental Duties were added to the Indian Constitution by which amendment?', question_media: noMedia,
      options: o(['42वाँ संशोधन', '42nd Amendment'], ['44वाँ संशोधन', '44th Amendment'], ['52वाँ संशोधन', '52nd Amendment'], ['73वाँ संशोधन', '73rd Amendment']), correct_option: 'A',
      solution_hi: '<b>42वें संविधान संशोधन (1976)</b> द्वारा स्वर्ण सिंह समिति की सिफारिश पर भाग 4(क), अनुच्छेद 51(क) में 10 मौलिक कर्तव्य जोड़े गए। 11वाँ कर्तव्य 86वें संशोधन (2002) से जुड़ा।',
      solution_en: '42nd Amendment (1976), on the Swaran Singh Committee recommendation — Part IVA, Article 51A.',
      key_concept: 'मौलिक कर्तव्य: अनुच्छेद 51A, भाग 4A, 42वाँ संशोधन',
    },
    {
      q_no: 29, section: 'सामान्य ज्ञान एवं समसायिक घटनाएं', topic: 'महत्वपूर्ण दिवस', syllabus_tag: 'सामान्य ज्ञान - दिवस', difficulty: 'Easy', passage_id: null,
      question_hi: "भारत में 'राष्ट्रीय खेल दिवस' कब मनाया जाता है? 🏑", question_en: "When is 'National Sports Day' celebrated in India? 🏑", question_media: noMedia,
      options: o(['15 अगस्त', '15 August'], ['29 अगस्त', '29 August'], ['5 सितंबर', '5 September'], ['14 नवंबर', '14 November']), correct_option: 'B',
      solution_hi: 'हॉकी के जादूगर <b>मेजर ध्यानचंद</b> की जयंती 29 अगस्त को राष्ट्रीय खेल दिवस मनाया जाता है। 5 सितंबर — शिक्षक दिवस, 14 नवंबर — बाल दिवस।',
      solution_en: '29 August — birth anniversary of hockey legend Major Dhyan Chand.',
      key_concept: '29 अगस्त — ध्यानचंद जयंती',
    },
    // ---------------- REASONING ----------------
    {
      q_no: 30, section: 'REASONING', topic: 'संख्या श्रृंखला', syllabus_tag: 'तर्कशक्ति - श्रृंखला', difficulty: 'Easy', passage_id: null,
      question_hi: 'श्रृंखला में अगली संख्या ज्ञात कीजिए: 2, 6, 12, 20, 30, ?', question_en: 'Find the next number in the series: 2, 6, 12, 20, 30, ?', question_media: noMedia,
      options: m('$40$', '$42$', '$44$', '$36$'), correct_option: 'B',
      solution_hi: 'अंतर: 4, 6, 8, 10, <b>12</b> → $30 + 12 = 42$<br>या $n(n+1)$: $1\\times2, 2\\times3, 3\\times4, 4\\times5, 5\\times6, 6\\times7 = 42$',
      solution_en: 'Differences 4, 6, 8, 10, 12 → 42; or $n(n+1)$ with $n=6$.',
      key_concept: 'पहले अंतर देखें; फिर $n(n+1)$, $n^2$, $n^3$ पैटर्न',
    },
    {
      q_no: 31, section: 'REASONING', topic: 'कोडिंग-डिकोडिंग', syllabus_tag: 'तर्कशक्ति - कोडिंग', difficulty: 'Easy', passage_id: null,
      question_hi: 'यदि किसी कूट भाषा में CAT = 24 लिखा जाता है, तो उसी भाषा में DOG को कैसे लिखा जाएगा?', question_en: 'If in a certain code CAT = 24, then how is DOG written in that code?', question_media: noMedia,
      options: m('$24$', '$25$', '$26$', '$27$'), correct_option: 'C',
      solution_hi: 'अक्षरों के स्थानीय मान जोड़े गए हैं: C(3)+A(1)+T(20) = 24<br>D(4)+O(15)+G(7) = <b>26</b>',
      solution_en: 'Sum of alphabet positions: C+A+T = 3+1+20 = 24, so D+O+G = 4+15+7 = 26.',
      key_concept: 'EJOTY: E-5, J-10, O-15, T-20, Y-25',
    },
    // ---------------- IT ----------------
    {
      q_no: 32, section: 'सूचना तकनीक', topic: 'मेमोरी इकाइयाँ', syllabus_tag: 'कंप्यूटर - मेमोरी', difficulty: 'Easy', passage_id: null,
      question_hi: '1 किलोबाइट (KB) में कितने बाइट होते हैं? 💾', question_en: 'How many bytes are there in 1 kilobyte (KB)? 💾', question_media: noMedia,
      options: m('1000', '1024', '512', '2048'), correct_option: 'B',
      solution_hi: 'कंप्यूटर बाइनरी (आधार 2) पर काम करता है: $1\\text{ KB} = 2^{10} = 1024$ बाइट। 1 MB = 1024 KB, 1 GB = 1024 MB।',
      solution_en: '$1\\text{ KB} = 2^{10} = 1024$ bytes.',
      key_concept: '1 Byte = 8 bit, 1 Nibble = 4 bit, 1 KB = $2^{10}$ Byte',
    },
    {
      q_no: 33, section: 'सूचना तकनीक', topic: 'कीबोर्ड शॉर्टकट', syllabus_tag: 'कंप्यूटर - MS Office', difficulty: 'Easy', passage_id: null,
      question_hi: 'चयनित टेक्स्ट को कॉपी करने की शॉर्टकट कुंजी कौन-सी है? ⌨️', question_en: 'Which shortcut key is used to copy the selected text? ⌨️', question_media: noMedia,
      options: m('Ctrl + V', 'Ctrl + X', 'Ctrl + C', 'Ctrl + Z'), correct_option: 'C',
      solution_hi: 'Ctrl + C = कॉपी, Ctrl + X = कट, Ctrl + V = पेस्ट, Ctrl + Z = अनडू (पिछला कार्य रद्द)।',
      solution_en: 'Ctrl+C copy, Ctrl+X cut, Ctrl+V paste, Ctrl+Z undo.',
      key_concept: 'C-Copy, X-Cut, V-Paste, Z-Undo, Y-Redo',
    },
    {
      q_no: 34, section: 'सूचना तकनीक', topic: 'इंटरनेट', syllabus_tag: 'कंप्यूटर - इंटरनेट एवं नेटवर्क', difficulty: 'Medium', passage_id: null,
      question_hi: 'वेब पेज बनाने के लिए प्रयुक्त भाषा HTML का पूर्ण रूप क्या है?', question_en: 'What is the full form of HTML, the language used to create web pages?', question_media: noMedia,
      options: m('Hyper Text Markup Language', 'High Text Machine Language', 'Hyperlink Transfer Markup Language', 'Home Tool Markup Language'), correct_option: 'A',
      solution_hi: 'HTML = <b>HyperText Markup Language</b>। इसे टिम बर्नर्स-ली ने बनाया। यह टैग (जैसे <code>&lt;p&gt;</code>, <code>&lt;table&gt;</code>) से वेब पेज की संरचना बनाती है।',
      solution_en: 'HTML stands for HyperText Markup Language, created by Tim Berners-Lee.',
      key_concept: 'HTML (संरचना), CSS (डिज़ाइन), JavaScript (व्यवहार)',
    },
  ],
};
