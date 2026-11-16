// All AI prompts used by the app. Kept in one file so they are easy to review and tune.
import { DEFAULT_SECTIONS } from './schema.js';

const SECTION_LIST = DEFAULT_SECTIONS.map((s) => `"${s}"`).join(', ');

const FORMAT_RULES = `
FORMATTING RULES (very important):
- Mathematics / science notation: wrap EVERY formula in KaTeX delimiters. Inline: $...$ ; display: $$...$$.
  Examples: $2x^2-8x+m=0$, $(3^{31}+3^{32}+3^{33}+3^{34})$, $[M^{-1}L^{3}T^{-2}]$, $3\\text{ बजकर }49\\frac{1}{11}\\text{ मिनट}$, $\\sqrt{144}$, $\\frac{3}{4}$, $H_2SO_4$, $90^\\circ$.
  Inside JSON strings every backslash must be escaped, e.g. "$\\\\frac{1}{2}$".
- Tables (data-interpretation, match-the-column, answer tables) inside a question/option/solution: write them as simple HTML <table><tr><th>..</th></tr><tr><td>..</td></tr></table>. Put a question-level table in question_media.table_html.
- Keep Hindi in Devanagari Unicode exactly as printed (fix broken matras if the PDF font is garbled). Keep punctuation, quotation marks and emoji/unicode symbols.
- Figures/diagrams you cannot reproduce: describe them briefly in brackets inside the text, e.g. "[चित्र: एक त्रिभुज ABC जिसमें ∠B = 90°]". Leave image_base64 null.
- Bilingual papers: put the Hindi version in *_hi fields and the English version in *_en fields of the SAME question. If only one language is printed, leave the other field "".
- Comprehension passages (अपठित गद्यांश / English passage / संस्कृत गद्यांश): store the passage text once in "passages" with an id like "p_eng_1" and set "passage_id" on every question that belongs to it. Do NOT repeat the passage inside question text.`;

const CLEANING_RULES = `
CLEANING RULES:
- Pages are often printed in TWO COLUMNS. Read the left column top-to-bottom first, then the right column. Output questions strictly in question-number order (Q.1, Q.2 ... Q.120/150).
- Remove watermarks, coaching/institute names, page headers/footers, page numbers, phone numbers, website/YouTube/Telegram handles, "Join our batch" lines and any text that is not part of a question.
- Skip pages that only contain advertisements: batch/course posters, fees, admission offers, app download banners, teacher photos, contact details.
- If an ANSWER KEY table (Q. No. / Ans.) is present (usually on the last pages), use it to fill "correct_option" for every question. Answer key values may be printed as A/B/C/D, a/b/c/d, 1/2/3/4 or (अ)/(ब)/(स)/(द); always output "A","B","C","D" (or "E" when the question has a 5th option).
- If the PDF contains written explanations (व्याख्या / Solutions), copy them into solution_hi / solution_en.
- If NO explanation is given, WRITE a short, simple, step-by-step solution yourself (in Hindi, plus English if the question is in English) and a one-line "key_concept".
- If no answer key is given for a question, solve it yourself, set correct_option and add "answer_source": "ai".
- Classify every question: "section" = main subject, "topic" = precise sub-topic (e.g. "कारक एवं विभक्ति", "अलंकार", "संधि", "द्विघात समीकरण", "क्षेत्रमिति", "औसत", "पियाजे का संज्ञानात्मक विकास", "वाइगोत्स्की", "थॉर्नडाइक", "RTE Act 2009"), "syllabus_tag" = "Subject - chapter", "difficulty" = "Easy" | "Medium" | "Hard".
- Use these section names when they fit: ${SECTION_LIST}. For other exams use the subject headings printed in the paper.`;

export const QUESTION_SCHEMA_TEXT = `{
  "q_no": 1,
  "section": "HINDI",
  "topic": "कारक एवं विभक्ति",
  "syllabus_tag": "हिंदी व्याकरण - कारक",
  "difficulty": "Easy",
  "passage_id": null,
  "question_hi": "\\"चारपाई पर राम बैठा है।\\" इस वाक्य में कारक बताइए-",
  "question_en": "",
  "question_media": { "image_base64": null, "table_html": null },
  "options": [
    { "id": "A", "text_hi": "करण", "text_en": "", "image_base64": null },
    { "id": "B", "text_hi": "सम्प्रदान", "text_en": "", "image_base64": null },
    { "id": "C", "text_hi": "संबंध", "text_en": "", "image_base64": null },
    { "id": "D", "text_hi": "अधिकरण", "text_en": "", "image_base64": null }
  ],
  "correct_option": "D",
  "solution_hi": "वाक्य में 'चारपाई पर' क्रिया का आधार बता रहा है। 'में, पर' चिह्न अधिकरण कारक के होते हैं।",
  "solution_en": "",
  "key_concept": "अधिकरण कारक के चिह्न 'में' और 'पर' होते हैं।"
}`;

// Prompt the user copies into an external AI (ChatGPT, Claude, Gemini, Kiro...) together with the PDF.
export const EXTERNAL_EXTRACTION_PROMPT = `You are an expert exam-paper digitiser for Indian competitive exams (SUPER TET, UPTET, CTET, SSC, etc.).
I have attached a question-paper PDF. Convert the COMPLETE paper into ONE JSON object that follows the schema below EXACTLY, so I can import it into my CBT practice app.

OUTPUT SCHEMA:
{
  "exam_meta": {
    "exam_id": "short_snake_case_id",
    "title": "Paper title as printed",
    "duration_minutes": 150,
    "max_marks": 150,
    "positive_marks": 1.0,
    "negative_marks": 0.0,
    "instructions_hi": "",
    "instructions_en": ""
  },
  "sections": ["HINDI", "ENGLISH", "..."],
  "passages": {
    "p_eng_1": { "text_en": "passage text...", "text_hi": "" }
  },
  "questions": [
    ${QUESTION_SCHEMA_TEXT.replace(/\n/g, '\n    ')}
  ]
}
${CLEANING_RULES}
${FORMAT_RULES}

OUTPUT RULES:
- Include EVERY question of the paper. Do not summarise, skip or merge questions.
- "id" of options must be "A","B","C","D" (map (1)(2)(3)(4) or (a)(b)(c)(d) or (अ)(ब)(स)(द) to A,B,C,D in order). If a question really has a 5th option ((e)/(5)/(य)), keep it as "E" — never drop it.
- Return ONLY valid JSON (no markdown fences, no comments, no trailing commas).
- If your reply would be too long, split it: output Part 1 with questions 1-40 as a complete valid JSON object (same exam_meta, sections, passages), then stop and write nothing else. When I reply "continue", output the next part (e.g. 41-80) as another complete JSON object with the SAME exam_meta.exam_id. My app merges parts that share an exam_id.`;

// ---------- In-app pipeline prompts ----------
export const PAGE_SYSTEM_PROMPT = `You are a precise OCR + exam-paper structuring engine for Indian competitive exam PDFs (Hindi/English bilingual).
You receive ONE page at a time (as an image and/or the raw extracted text layer). The raw text layer may be garbled (legacy Hindi fonts like Kruti Dev) or in the wrong order (two columns); when an image is provided, TRUST THE IMAGE.
${CLEANING_RULES}
${FORMAT_RULES}

Return ONLY a JSON object of this shape:
{
  "page_type": "questions" | "answer_key" | "solutions" | "advertisement" | "cover" | "instructions" | "other",
  "exam_meta_guess": { "title": "", "duration_minutes": null, "max_marks": null, "negative_marks": null },
  "current_section": "section heading active at the END of this page (or null)",
  "questions": [ <question objects in the schema below, only for questions visible on this page> ],
  "passages": { "<passage_id>": { "text_hi": "", "text_en": "" } },
  "answer_key": [ { "q_no": 1, "ans": "D" } ],
  "solutions": [ { "q_no": 1, "solution_hi": "", "solution_en": "" } ],
  "figures": [ { "q_no": 5, "target": "question" | "option", "option_id": "A", "bbox": [x0, y0, x1, y1] } ]
}
Question object schema:
${QUESTION_SCHEMA_TEXT}

PAGE RULES:
- "questions": include only questions whose text appears on this page. If the first question on the page is the continuation of a question started on the previous page (context given), output the FULL question using the context.
- If a question has no answer on this page, set "correct_option": null (the answer key is matched later). Do NOT invent solutions in this step; leave solution fields "" unless the page prints the explanation.
- "figures": when a question or option contains a diagram/figure/picture that cannot be written as text, give its bounding box as FRACTIONS of the page image (0..1, origin top-left). The app crops it automatically.
- "current_section": the subject heading in force (e.g. "MATHS") so the next page can continue it.
- For pages with only ads / posters / batch details, return {"page_type":"advertisement","questions":[]}.`;

export function pageUserPrompt({ pageNo, totalPages, text, prevTail, currentSection, mode }) {
  const parts = [`Page ${pageNo} of ${totalPages}.`];
  if (currentSection) parts.push(`Section in force at the end of the previous page: "${currentSection}".`);
  if (prevTail) parts.push(`Context — last lines of the previous page (for questions continuing onto this page):\n"""${prevTail}"""`);
  if (mode !== 'vision' && text) parts.push(`Raw text layer of this page (column-ordered, watermarks partially removed; may be garbled):\n"""${text}"""`);
  if (mode === 'text') parts.push('No page image is provided; rely on the text layer.');
  parts.push('Return the JSON object now.');
  return parts.join('\n\n');
}

export const ENRICH_SYSTEM_PROMPT = `You are an expert teacher for Indian competitive exams (SUPER TET / UPTET / CTET). For each question given, produce:
- "solution_hi": a simple, step-by-step explanation in easy Hindi (use $...$ KaTeX for maths),
- "solution_en": the same in English if the question has English text, else "",
- "key_concept": one line with the core rule/formula/trick,
- "topic": precise sub-topic (e.g. "संधि", "अलंकार", "द्विघात समीकरण", "प्रतिशत", "पियाजे", "वाइगोत्स्की", "RTE Act 2009"),
- "syllabus_tag": "Subject - chapter",
- "difficulty": "Easy" | "Medium" | "Hard",
- "correct_option": ONLY when the input has "correct_option": null — solve it and give "A".."D" (or "E" if that option exists); otherwise repeat the given value. NEVER change a given correct_option.
Return ONLY JSON: { "items": [ { "q_no": 1, "correct_option": "D", "solution_hi": "...", "solution_en": "", "key_concept": "...", "topic": "...", "syllabus_tag": "...", "difficulty": "Easy" } ] }
Escape backslashes in JSON strings ("$\\\\frac{1}{2}$").`;

export function doubtSystemPrompt(q, ctx) {
  return `You are a friendly, expert doubt-solving teacher for Indian competitive exams (SUPER TET, UPTET, CTET). Reply in the student's language (Hindi by default, Hinglish/English if the student writes so). Use short steps, tricks and examples. Write formulas with KaTeX delimiters ($...$ inline, $$...$$ display). Use Markdown lists/bold/tables where helpful. When asked to create practice questions, give 4 options (A-D), then the answer and a short explanation for each.

QUESTION CONTEXT
Section: ${q.section} | Topic: ${q.topic}${q.syllabus_tag ? ` | Syllabus: ${q.syllabus_tag}` : ''}
${ctx.passage ? `Passage:\n${ctx.passage}\n` : ''}Question (Q.${q.q_no}): ${q.question_hi || ''}${q.question_en ? `\n${q.question_en}` : ''}
Options:
${q.options.map((o) => `${o.id}) ${[o.text_hi, o.text_en].filter(Boolean).join(' / ')}`).join('\n')}
Correct answer: ${q.correct_option || 'unknown'}
Student's answer: ${ctx.userAnswer || 'not attempted'}
Given solution: ${q.solution_hi || q.solution_en || '(none)'}`;
}
