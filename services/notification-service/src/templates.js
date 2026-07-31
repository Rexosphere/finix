/**
 * Multi-locale notification templates for FINIX M8.
 * Locales: en (English), si (Sinhala), ta (Tamil).
 */

const TEMPLATES = {
  transfer_receipt: {
    en: {
      subject: "Transfer receipt",
      body: "Hi {{name}}, you sent {{amount}} {{currency}} to {{creditor}}. Ref: {{ref}}.",
    },
    si: {
      subject: "මාරු කිරීමේ රිසිට්පත",
      body: "ආයුබෝවන් {{name}}, ඔබ {{creditor}} වෙත {{amount}} {{currency}} යැව්වේය. යොමු: {{ref}}.",
    },
    ta: {
      subject: "பரிமாற்ற ரசீது",
      body: "வணக்கம் {{name}}, நீங்கள் {{creditor}} க்கு {{amount}} {{currency}} அனுப்பினீர்கள். குறிப்பு: {{ref}}.",
    },
  },
  step_up_challenge: {
    en: {
      subject: "Verify your transfer",
      body: "FINIX security: enter code {{code}} to approve transfer of {{amount}} {{currency}}. Expires in {{minutes}} minutes.",
    },
    si: {
      subject: "ඔබේ මාරුව සනාථ කරන්න",
      body: "FINIX ආරක්ෂාව: {{amount}} {{currency}} මාරුව අනුමත කිරීමට {{code}} කේතය ඇතුළත් කරන්න. {{minutes}} මිනිත්තුවකින් කල් ඉකුත් වේ.",
    },
    ta: {
      subject: "உங்கள் பரிமாற்றத்தை உறுதிப்படுத்தவும்",
      body: "FINIX பாதுகாப்பு: {{amount}} {{currency}} பரிமாற்றத்தை அங்கீகரிக்க {{code}} குறியீட்டை உள்ளிடவும். {{minutes}} நிமிடங்களில் காலாவதியாகும்.",
    },
  },
  loan_approved: {
    en: {
      subject: "Loan approved",
      body: "Congratulations {{name}}! Your loan of {{amount}} {{currency}} ({{product}}) was approved. First due: {{dueDate}}.",
    },
    si: {
      subject: "ණය අනුමතයි",
      body: "සුභ පැතුම් {{name}}! ඔබේ {{amount}} {{currency}} ({{product}}) ණය අනුමත විය. පළමු ගෙවීම: {{dueDate}}.",
    },
    ta: {
      subject: "கடன் அனுமதிக்கப்பட்டது",
      body: "வாழ்த்துக்கள் {{name}}! உங்கள் {{amount}} {{currency}} ({{product}}) கடன் அனுமதிக்கப்பட்டது. முதல் தவணை: {{dueDate}}.",
    },
  },
  fraud_alert: {
    en: {
      subject: "Fraud alert",
      body: "Alert {{name}}: unusual activity on account {{account}} ({{detail}}). If this wasn't you, call FINIX support.",
    },
    si: {
      subject: "වංචා අනතුරු ඇඟවීම",
      body: "අනතුරු ඇඟවීම {{name}}: {{account}} ගිණුමේ අසාමාන්‍ය ක්‍රියාකාරකම් ({{detail}}). ඔබ නොවේ නම් FINIX සහාය අමතන්න.",
    },
    ta: {
      subject: "மோசடி எச்சரிக்கை",
      body: "எச்சரிக்கை {{name}}: {{account}} கணக்கில் அசாதாரண செயல்பாடு ({{detail}}). இது நீங்கள் இல்லையெனில் FINIX ஆதரவை அழைக்கவும்.",
    },
  },
};

function interpolate(text, vars) {
  return text.replace(/\{\{(\w+)\}\}/g, (_, key) => {
    if (vars[key] === undefined || vars[key] === null) {
      return `{{${key}}}`;
    }
    return String(vars[key]);
  });
}

export function listTemplates() {
  return Object.keys(TEMPLATES);
}

export function renderTemplate(name, locale, vars = {}) {
  const byLocale = TEMPLATES[name];
  if (!byLocale) {
    throw new Error(
      `unknown template "${name}"; known: ${listTemplates().join(", ")}`,
    );
  }
  const entry = byLocale[locale];
  if (!entry) {
    throw new Error(`locale "${locale}" not available for template "${name}"`);
  }
  return {
    subject: interpolate(entry.subject, vars),
    body: interpolate(entry.body, vars),
  };
}

export { TEMPLATES };
