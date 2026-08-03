package com.blink.dtn.auth

/**
 * Random display-name stub when the user leaves «Имя» empty.
 * All names are ≤20 chars (mesh / UI field limit).
 */
object DinoNameGenerator {
    const val MAX_LEN = 20

    /** 50 species / short forms — Russian. */
    private val NAMES_RU = listOf(
        "Рекс",
        "Раптор",
        "Дино",
        "Тираннозавр",
        "Велоцираптор",
        "Трицератопс",
        "Стегозавр",
        "Брахиозавр",
        "Анкилозавр",
        "Диплодок",
        "Птеранодон",
        "Спинозавр",
        "Аллозавр",
        "Игуанодон",
        "Паразавролоф",
        "Компсогнат",
        "Дейноних",
        "Карнотавр",
        "Гиганотозавр",
        "Археоптерикс",
        "Плезиозавр",
        "Мозазавр",
        "Диметродон",
        "Овираптор",
        "Троодон",
        "Галлимим",
        "Коритозавр",
        "Майазавр",
        "Протоцератопс",
        "Пахицефалозавр",
        "Цератозавр",
        "Мегалозавр",
        "Барионикс",
        "Сухомим",
        "Уранозавр",
        "Ламбеозавр",
        "Эдмонтозавр",
        "Дромеозавр",
        "Микрораптор",
        "Синорнитозавр",
        "Ютараптор",
        "Кентрозавр",
        "Скутелозавр",
        "Платеозавр",
        "Эораптор",
        "Герреразавр",
        "Коелофиз",
        "Дилофозавр",
        "Теризинозавр",
        "Бронтозавр"
    )

    /** 50 species / short forms — English. */
    private val NAMES_EN = listOf(
        "Rex",
        "Raptor",
        "Dino",
        "T-Rex",
        "Velociraptor",
        "Triceratops",
        "Stegosaurus",
        "Brachiosaurus",
        "Ankylosaurus",
        "Diplodocus",
        "Pteranodon",
        "Spinosaurus",
        "Allosaurus",
        "Iguanodon",
        "Parasaurolophus",
        "Compsognathus",
        "Deinonychus",
        "Carnotaurus",
        "Giganotosaurus",
        "Archaeopteryx",
        "Plesiosaur",
        "Mosasaurus",
        "Dimetrodon",
        "Oviraptor",
        "Troodon",
        "Gallimimus",
        "Corythosaurus",
        "Maiasaura",
        "Protoceratops",
        "Pachycephalosaur",
        "Ceratosaurus",
        "Megalosaurus",
        "Baryonyx",
        "Suchomimus",
        "Ouranoosaurus",
        "Lambeosaurus",
        "Edmontosaurus",
        "Dromaeosaurus",
        "Microraptor",
        "Sinornithosaur",
        "Utahraptor",
        "Kentrosaurus",
        "Scutellosaurus",
        "Plateosaurus",
        "Eoraptor",
        "Herrerasaurus",
        "Coelophysis",
        "Dilophosaurus",
        "Therizinosaur",
        "Brontosaurus"
    )

    init {
        require(NAMES_RU.size == 50) { "Need 50 RU dino names, got ${NAMES_RU.size}" }
        require(NAMES_EN.size == 50) { "Need 50 EN dino names, got ${NAMES_EN.size}" }
        require(NAMES_RU.all { it.length <= MAX_LEN }) { "RU name exceeds $MAX_LEN" }
        require(NAMES_EN.all { it.length <= MAX_LEN }) { "EN name exceeds $MAX_LEN" }
    }

    fun random(lang: String = "ru"): String {
        val list = if (lang == "en") NAMES_EN else NAMES_RU
        return list.random().take(MAX_LEN)
    }

    /** Resolve display name: trimmed input or random dino stub. */
    fun resolveDisplayName(raw: String?, lang: String = "ru"): String {
        val trimmed = raw?.trim().orEmpty().take(MAX_LEN)
        return trimmed.ifEmpty { random(lang) }
    }
}
