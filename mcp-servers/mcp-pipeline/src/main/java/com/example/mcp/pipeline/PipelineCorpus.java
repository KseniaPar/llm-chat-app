package com.example.mcp.pipeline;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.Optional;

public final class PipelineCorpus {

    record Item(String title, String snippet, String url, List<String> keywords) {
    }

    private static final List<Item> ENTRIES = List.of(
            new Item(
                    "Шахада — первый столп ислама",
                    "Шахада — двойное свидетельство веры: «Нет божества, кроме Аллаха, и Мухаммед — посланник Аллаха». "
                            + "Произнесение шахады — вход в ислам.",
                    "corpus://islam/shahada",
                    List.of("шахада", "столп", "ислам", "свидетельство")),
            new Item(
                    "Намаз — второй столп",
                    "Намаз (салят) — обязательная молитва пять раз в сутки: фаджр, зухр, аср, магриб, иша. "
                            + "Связан с ориентацией на Каабу в Мекке.",
                    "corpus://islam/salat",
                    List.of("намаз", "салят", "молитва", "столп", "ислам")),
            new Item(
                    "Закят — третий столп",
                    "Закят — обязательная милостыня для обеспечения нуждающихся мусульман. "
                            + "Обычно 2,5% от сбережений, превышающих нисаб.",
                    "corpus://islam/zakat",
                    List.of("закят", "милостыня", "столп", "ислам")),
            new Item(
                    "Пост в Рамадан — четвёртый столп",
                    "Сауm (пост) в месяц Рамадан — воздержание от еды, питья и интимных отношений от рассвета до заката. "
                            + "Укрепляет духовную дисциплину.",
                    "corpus://islam/ramadan",
                    List.of("пост", "рамадан", "саум", "столп", "ислам")),
            new Item(
                    "Хадж — пятый столп",
                    "Хадж — паломничество в Мекку хотя бы раз в жизни для способного мусульманина. "
                            + "Включает обход Каабы, стояние на горе Арафат и другие обряды.",
                    "corpus://islam/hajj",
                    List.of("хадж", "мекка", "кааба", "паломничество", "столп", "ислам")),
            new Item(
                    "Пять столпов ислама — обзор",
                    "Пять столпов ислама: шахада, намаз, закят, пост в Рамадан и хадж. "
                            + "Это основные практические обязанности мусульманина наряду с шестью столпами веры.",
                    "corpus://islam/five-pillars",
                    List.of("пять", "столпов", "столп", "ислам", "обзор")),
            new Item(
                    "Шесть столпов веры (иман)",
                    "Иман включает: вера в Аллаха, ангелов, писания, пророков, Судный день и предопределение. "
                            + "Отличается от пяти столпов практики (ислам как действие).",
                    "corpus://islam/iman",
                    List.of("иман", "столпов", "вера", "ислам")),
            new Item(
                    "Коран как священное писание",
                    "Коран — главное писание ислама, открытое пророку Мухаммеду. "
                            + "Читается на арабском; переводы используются для понимания.",
                    "corpus://islam/quran",
                    List.of("коран", "ислам", "писание")),
            new Item(
                    "Мечеть и коллективная молитва",
                    "Мечеть — место общей молитвы по пятницам (джума-намаз) и ежедневных намазов. "
                            + "Минaret и михраб помогают ориентироваться в пространстве молитвы.",
                    "corpus://islam/mosque",
                    List.of("мечеть", "намаз", "ислам")),
            new Item(
                    "Шариат — источники исламского права",
                    "Шариат — исламская правовая система, основанная на четырёх источниках: "
                            + "Коран, Сунна, иджма (единогласие учёных) и кийас (судебное сравнение). "
                            + "Регулирует поклонение, семейные и гражданские отношения.",
                    "corpus://islam/sharia",
                    List.of("шариат", "ислам", "коран", "сунна", "иджма", "кийас")),
            new Item(
                    "Ислам в религиоведении",
                    "На экзамене по религиоведению часто спрашивают пять столпов, отличие суннитов и шиитов, "
                            + "роль пророка и понятие уммы.",
                    "corpus://islam/exam",
                    List.of("экзамен", "религиоведение", "ислам", "столпов")));

    private PipelineCorpus() {
    }

    public static List<Map<String, Object>> searchPublic(String query) {
        return search(query);
    }

    public static Optional<Map<String, Object>> itemByUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return ENTRIES.stream()
                .filter(entry -> url.equals(entry.url()))
                .findFirst()
                .map(PipelineCorpus::toItemMap);
    }

    static List<Map<String, Object>> search(String query) {
        String normalized = McpEncodingFix.normalize(query);
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        String[] tokens = lower.split("\\s+");

        return ENTRIES.stream()
                .filter(entry -> matches(entry, lower, tokens))
                .limit(8)
                .map(PipelineCorpus::toItemMap)
                .toList();
    }

    private static boolean matches(Item entry, String lowerQuery, String[] tokens) {
        String haystack = (entry.title() + " " + entry.snippet() + " " + String.join(" ", entry.keywords()))
                .toLowerCase(Locale.ROOT);
        if (haystack.contains(lowerQuery)) {
            return true;
        }
        int hits = 0;
        for (String token : tokens) {
            if (token.length() < 3) {
                continue;
            }
            if (haystack.contains(token)) {
                hits++;
            }
        }
        return hits >= Math.min(2, tokens.length) || (tokens.length == 1 && hits == 1);
    }

    private static Map<String, Object> toItemMap(Item entry) {
        return Map.of(
                "title", entry.title(),
                "snippet", entry.snippet(),
                "url", entry.url());
    }
}
