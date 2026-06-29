package com.example.mcp.study;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;

public final class StudyTopicsSeed {

    public static final int MIN_TOPICS = 20;

    private StudyTopicsSeed() {
    }

    public static void ensureSeeded(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS study_topics (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    subject TEXT NOT NULL,
                    topic TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    exam_hints TEXT NOT NULL
                )
                """);

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM study_topics", Integer.class);
        if (count != null && count >= MIN_TOPICS) {
            ensureSupplementalTopics(jdbcTemplate);
            patchTopicCorrections(jdbcTemplate);
            return;
        }

        if (count != null && count > 0) {
            jdbcTemplate.execute("DELETE FROM study_topics");
        }

        for (TopicSeed seed : topics()) {
            jdbcTemplate.update(
                    """
                            INSERT INTO study_topics (subject, topic, summary, exam_hints)
                            VALUES (?, ?, ?, ?)
                            """,
                    seed.subject(),
                    seed.topic(),
                    seed.summary(),
                    seed.examHints());
        }
        ensureSupplementalTopics(jdbcTemplate);
        patchTopicCorrections(jdbcTemplate);
    }

    /**
     * Fixes known bad text in already-seeded rows (typos, mixed Latin/Cyrillic).
     */
    public static void patchTopicCorrections(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                """
                        UPDATE study_topics
                        SET summary = ?, exam_hints = ?
                        WHERE LOWER(topic) = 'шариат'
                        """,
                "Шариат — исламская правовая система, основанная на четырёх источниках: "
                        + "Коран (откровение), Сунна (учение и практика Пророка), "
                        + "иджма (единогласие учёных) и кийас (судебное сравнение по аналогии).",
                "Не сводить шариат к медиа-стереотипам; назвать четыре источника.");
    }

    /**
     * Inserts demo topics that may be missing when the DB was seeded before they were added.
     */
    public static void ensureSupplementalTopics(JdbcTemplate jdbcTemplate) {
        for (TopicSeed seed : supplementalTopics()) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM study_topics WHERE LOWER(topic) = LOWER(?)",
                    Integer.class,
                    seed.topic());
            if (exists != null && exists == 0) {
                jdbcTemplate.update(
                        """
                                INSERT INTO study_topics (subject, topic, summary, exam_hints)
                                VALUES (?, ?, ?, ?)
                                """,
                        seed.subject(),
                        seed.topic(),
                        seed.summary(),
                        seed.examHints());
            }
        }
    }

    private static List<TopicSeed> supplementalTopics() {
        return List.of(
                new TopicSeed(
                        "ислам",
                        "иман — шесть столпов веры",
                        "Иман включает: вера в Аллаха, ангелов, писания, пророков, Судный день и предопределение. "
                                + "Отличается от пяти столпов практики (ислам как действие).",
                        "Перечислить шесть столпов веры и отличить их от пяти столпов ислама."));
    }

    public static List<TopicSeed> topics() {
        return List.of(
                new TopicSeed(
                        "буддизм",
                        "четыре благородные истины",
                        "Учение Будды: страдание, происхождение страдания, прекращение страдания и путь к его прекращению.",
                        "Знать все четыре формулировки и кратко объяснить каждую."),
                new TopicSeed(
                        "буддизм",
                        "восьмеричный путь",
                        "Практический путь: правильные взгляды, намерения, речь, действие, занятость, усилие, осознанность, сосредоточение.",
                        "Перечислить восемь составляющих и связать с четвёртой благородной истиной."),
                new TopicSeed(
                        "буддизм",
                        "нирвана",
                        "Состояние прекращения страдания и освобождения от цикла перерождений.",
                        "Отличать нирвану от рая в теистических религиях."),
                new TopicSeed(
                        "буддизм",
                        "кarma в буддизме",
                        "Намеренные действия формируют последствия; карма — этическая причинность без постоянной души.",
                        "Сравнить с кармой в индуизме."),
                new TopicSeed(
                        "буддизм",
                        "сангха",
                        "Община монахов и монахинь — одна из трёх драгоценностей вместе с Буддой и Дарма.",
                        "Назвать три драгоценности и роль сангхи."),
                new TopicSeed(
                        "буддизм",
                        "три драгоценности",
                        "Будда, Дарма и Сангха — опора практикующего буддиста.",
                        "Перечислить три драгоценности и их значение."),
                new TopicSeed(
                        "индуизм",
                        "karma",
                        "Закон причинно-следственной связи действий и их последствий в этой и последующих жизнях.",
                        "Объяснить karma без упрощений «наказание/награда»."),
                new TopicSeed(
                        "индуизм",
                        "самсара",
                        "Цикл перерождений, в котором атман проходит через различные формы бытия.",
                        "Связать samсara с karma и moksha."),
                new TopicSeed(
                        "индуизм",
                        "мокша",
                        "Освобождение от samsara — конечная цель в большинстве индуистских школ.",
                        "Отличить moksha от буддийской nirvana."),
                new TopicSeed(
                        "индуизм",
                        "варны и ашрамы",
                        "Социальная модель варн и четыре жизненных ашрама.",
                        "Знать учебное определение модели."),
                new TopicSeed(
                        "индуизм",
                        "тримурти",
                        "Бrahma, Vishnu и Shiva как три формы высшего божества.",
                        "Не путать Brahma и Brahman."),
                new TopicSeed(
                        "христианство",
                        "никейский символ веры",
                        "Исповедание основ христианской доктрины, принятое на I Вселенском соборе в Никее.",
                        "Знать положения о единосущии Отца и Сына."),
                new TopicSeed(
                        "христианство",
                        "таинства",
                        "Ритуальные знаки благодати: крещение, eucharistia и другие.",
                        "Назвать основные таинства и их роль."),
                new TopicSeed(
                        "христианство",
                        "протестантская реформация",
                        "Движение XVI века: sola scriptura, sola fide.",
                        "Назвать ключевые лозунги реформации."),
                new TopicSeed(
                        "ислам",
                        "пять столпов ислама",
                        "Шахада, салят, закят, саум, хадж — базовые обязанности мусульманина.",
                        "Назвать все пять столпов с пояснением."),
                new TopicSeed(
                        "ислам",
                        "иман — шесть столпов веры",
                        "Иман включает: вера в Аллаха, ангелов, писания, пророков, Судный день и предопределение. "
                                + "Отличается от пяти столпов практики (ислам как действие).",
                        "Перечислить шесть столпов веры и отличить их от пяти столпов ислама."),
                new TopicSeed(
                        "ислам",
                        "коран и сунна",
                        "Коран — слово Аллаха; Сунна — учение и пример Пророка.",
                        "Различать Коран и хадисы."),
                new TopicSeed(
                        "ислам",
                        "шариат",
                        "Шариат — исламская правовая система, основанная на четырёх источниках: "
                                + "Коран (откровение), Сунна (учение и практика Пророка), "
                                + "иджма (единогласие учёных) и кийас (судебное сравнение по аналогии).",
                        "Не сводить шариат к медиа-стереотипам; назвать четыре источника."),
                new TopicSeed(
                        "иудаизм",
                        "таурат",
                        "Тора — первые пять книг Танаха; центральный текст иудаизма.",
                        "Не путать Тору с Тalmud."),
                new TopicSeed(
                        "иудаизм",
                        "талмуд",
                        "Сборник раввинистических комментариев к Мишне.",
                        "Объяснить различие письменной и устной Торы."),
                new TopicSeed(
                        "иудаизм",
                        "шаббат",
                        "Седьмой день отдыха и святости.",
                        "Знать символику субботнего покоя."),
                new TopicSeed(
                        "религиоведение",
                        "сакральное и профанное",
                        "Дихотомия Дюркгейма: сacred отделено от profane.",
                        "Пример сacralization пространства в разных религиях."),
                new TopicSeed(
                        "религиоведение",
                        "ритуал",
                        "Последовательность символических действий, закрепляющая релigious смысл.",
                        "Различать обряд, церемонию и liturgy."),
                new TopicSeed(
                        "религиоведение",
                        "протестантская этика и дух капитализма",
                        "Гипотеза М. Вебера о связи протестантского аскетизма и капитализма.",
                        "Кратко изложить тезис Вебера."),
                new TopicSeed(
                        "религиоведение",
                        "мировые религии",
                        "Буддизм, христианство и ислам — распространены за пределами одного региона.",
                        "Назвать три мировые религии и их ареал."));
    }

    public record TopicSeed(String subject, String topic, String summary, String examHints) {
    }

    public static List<TopicSeed> searchLocal(String query, String subject) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String lowerQuery = query.trim().toLowerCase(Locale.ROOT);
        String lowerSubject = subject != null && !subject.isBlank() ? subject.trim().toLowerCase(Locale.ROOT) : null;
        return topics().stream()
                .filter(seed -> lowerSubject == null || seed.subject().toLowerCase(Locale.ROOT).contains(lowerSubject))
                .filter(seed -> matchesQuery(seed, lowerQuery))
                .limit(10)
                .toList();
    }

    private static boolean matchesQuery(TopicSeed seed, String lowerQuery) {
        String haystack = (seed.topic() + " " + seed.summary() + " " + seed.examHints()).toLowerCase(Locale.ROOT);
        if (haystack.contains(lowerQuery)) {
            return true;
        }
        for (String word : lowerQuery.split("\\s+")) {
            if (word.length() >= 3 && haystack.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
