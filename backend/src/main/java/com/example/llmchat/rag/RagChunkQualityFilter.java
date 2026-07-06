package com.example.llmchat.rag;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class RagChunkQualityFilter {

    private static final Pattern TOC_DOT_LEADER = Pattern.compile("(?:\\.\\s*){3,}\\s*\\d{1,4}\\s*$", Pattern.MULTILINE);
    private static final Pattern YEAR_PAGE_SECTION = Pattern.compile("^\\d{4}\\.\\s*[Сс]\\.\\s*\\d+\\.?$");
    private static final Pattern PUBLICATION_CITATION = Pattern.compile(
            "(?i)(?:\\bм\\.|\\bкиев|\\bбелосток|\\bспб\\.|\\bмосква)[,\\s]+(?:\\(?(?:19|20)\\d{2}\\)?)");
    private static final Pattern NUMBERED_BIBLIO_LINE = Pattern.compile(
            "^\\d+\\.\\s+(?:[\\p{Lu}][\\p{L}''\\-]+\\s*\\([\\p{L}''\\-\\s]+\\)|[\\p{Lu}][\\p{L}''\\-]+\\s+[А-ЯA-Z]\\.\\s*[А-ЯA-Z]\\.)",
            Pattern.UNICODE_CASE);
    private static final Pattern ACADEMIC_TITLE = Pattern.compile(
            "(?i)(?:архиеп|свт|блж|прот|иером|проф|акад)\\.");

    public boolean isBibliographyOrNavigation(String section, String content) {
        String sectionText = section != null ? section.trim() : "";
        String contentText = content != null ? content.trim() : "";
        if (sectionText.isEmpty() && contentText.isEmpty()) {
            return true;
        }

        if (YEAR_PAGE_SECTION.matcher(sectionText).matches()) {
            return true;
        }
        if (contentText.startsWith("К разделу") || sectionText.startsWith("К разделу")) {
            return true;
        }
        if (sectionText.matches("(?i).+\\.pdf$")) {
            return true;
        }
        if (isTableOfContentsChunk(sectionText, contentText)) {
            return true;
        }
        if (isBibliographyEntry(sectionText, contentText)) {
            return true;
        }
        return false;
    }

    private boolean isTableOfContentsChunk(String section, String content) {
        if (TOC_DOT_LEADER.matcher(section).find()
                || section.matches("(?i).*(?:раздел|глава|часть|отдел|параграф).*\\.\\s*\\.\\s*\\.")) {
            return true;
        }
        return content.length() < 500 && TOC_DOT_LEADER.matcher(content).find();
    }

    private boolean isBibliographyEntry(String section, String content) {
        if (content.length() > 700) {
            return false;
        }

        String combined = (section + "\n" + content).toLowerCase(Locale.ROOT);
        boolean numberedLine = NUMBERED_BIBLIO_LINE.matcher(section).find()
                || NUMBERED_BIBLIO_LINE.matcher(content.lines().findFirst().orElse("")).find();
        boolean publicationMeta = PUBLICATION_CITATION.matcher(combined).find()
                || combined.matches("(?s).*(?:19|20)\\d{2}\\s*\\.?\\s*$");
        boolean academicAuthor = ACADEMIC_TITLE.matcher(combined).find();

        if (numberedLine && publicationMeta) {
            return true;
        }
        if (numberedLine && academicAuthor && content.length() < 550) {
            return true;
        }
        if (section.matches("^\\d+\\.\\s+.*")) {
            // duplicate title + year only, typical for bibliography list items
            String compactSection = normalize(section);
            String compactContent = normalize(content);
            if (compactContent.length() < 420
                    && compactContent.startsWith(compactSection.substring(0, Math.min(35, compactSection.length())))
                    && publicationMeta) {
                return true;
            }
        }
        return content.length() < 280 && publicationMeta && !content.contains("?");
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
