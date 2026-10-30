export function generateTitleVariants(rawTitle: string): string[] {
  if (!rawTitle) return [];
  const variants = new Set<string>();

  // 1. Raw trimmed
  variants.add(rawTitle.trim());

  // 2. Clean punctuation
  const clean = rawTitle
    .replace(/&#?\w+;/g, ' ')
    .replace(/[^\w\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  variants.add(clean);

  // 3. Normalize "v160" / "vol 160" / "#160" -> "160"
  const noV = clean
    .replace(/\b[vV](\d+)\b/g, '$1')
    .replace(/\bvol(?:ume)?\s*(\d+)\b/gi, '$1')
    .replace(/\s+/g, ' ')
    .trim();
  variants.add(noV);

  // Also include "v" prefix if number was standalone (e.g. "Vixen 160" -> "Vixen v160")
  const withV = clean.replace(/\b(?<![vV])(\d{2,4})\b/g, 'v$1');
  if (withV !== clean) {
    variants.add(withV);
  }

  // 4. Roman numerals <-> numbers (e.g. "II" <-> "2")
  const romanMap: Record<string, string> = {
    '\\bII\\b': '2',
    '\\bIII\\b': '3',
    '\\bIV\\b': '4',
    '\\bV\\b': '5',
    '\\bVI\\b': '6',
    '\\bVII\\b': '7',
    '\\bVIII\\b': '8',
    '\\bIX\\b': '9',
    '\\bX\\b': '10',
  };

  let romanReplaced = clean;
  for (const [pattern, num] of Object.entries(romanMap)) {
    romanReplaced = romanReplaced.replace(new RegExp(pattern, 'gi'), num);
  }
  variants.add(romanReplaced);

  // 5. Studio prefix stripping (e.g. "Vixen - Title" -> "Title")
  const studioPrefixRegex =
    /^(?:Vixen|Blacked|Tushy|Brazzers|Naughty\s*America|Evil\s*Angel|Reality\s*Kings|Sweet\s*Sinner|Wicked|Digital\s*Playground|Jules\s*Jordan)\s*[-:]?\s*/i;
  if (studioPrefixRegex.test(clean)) {
    const stripped = clean.replace(studioPrefixRegex, '').trim();
    if (stripped.length > 2) {
      variants.add(stripped);
      variants.add(stripped.replace(/\b[vV](\d+)\b/g, '$1'));
    }
  }

  return Array.from(variants).filter((v) => v.length > 1);
}
