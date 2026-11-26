package com.rutabikersync.app.feature.groups

enum class DecisionAudience(val apiValue: String, val label: String) {
    DIRECTIVA("DIRECTIVA", "Directiva"),
    FOUNDERS("FOUNDERS", "Fundadores"),
    FULL_MEMBERS("FULL_MEMBERS", "Full Members"),
    PROSPECTS("PROSPECTS", "Prospectos");

    companion object {
        fun fromApi(value: String?): DecisionAudience? =
            entries.find { it.apiValue.equals(value, ignoreCase = true) }
    }
}

enum class DecisionCategory(val apiValue: String, val label: String) {
    ROUTE("route", "Ruta"),
    MEMBERSHIP("membership", "Membresía"),
    FINANCE("finance", "Fondo / Evento"),
    GOVERNANCE("governance", "Directiva"),
    GENERAL("general", "General");

    companion object {
        fun fromApi(value: String?): DecisionCategory =
            entries.find { it.apiValue.equals(value, ignoreCase = true) } ?: GENERAL
    }
}

data class DecisionTemplate(
    val label: String,
    val category: DecisionCategory,
    val defaultAudiences: Set<DecisionAudience>,
    val defaultOptions: List<String>,
    val defaultMinQuorumPercent: Int = 0
)

val OFFICIAL_DECISION_TEMPLATES: List<DecisionTemplate> = listOf(
    DecisionTemplate(
        label = "Ruta de salida",
        category = DecisionCategory.ROUTE,
        defaultAudiences = setOf(DecisionAudience.FULL_MEMBERS, DecisionAudience.PROSPECTS),
        defaultOptions = listOf("Opción A", "Opción B", "Abstención"),
        defaultMinQuorumPercent = 0
    ),
    DecisionTemplate(
        label = "Ascenso de miembro",
        category = DecisionCategory.MEMBERSHIP,
        defaultAudiences = setOf(
            DecisionAudience.DIRECTIVA,
            DecisionAudience.FOUNDERS,
            DecisionAudience.FULL_MEMBERS
        ),
        defaultOptions = listOf("A favor", "En contra", "Abstención"),
        defaultMinQuorumPercent = 50
    ),
    DecisionTemplate(
        label = "Fondo / Evento",
        category = DecisionCategory.FINANCE,
        defaultAudiences = setOf(DecisionAudience.DIRECTIVA, DecisionAudience.FULL_MEMBERS),
        defaultOptions = listOf("Aprobar", "Rechazar", "Abstención"),
        defaultMinQuorumPercent = 0
    ),
    DecisionTemplate(
        label = "Interna Directiva",
        category = DecisionCategory.GOVERNANCE,
        defaultAudiences = setOf(DecisionAudience.DIRECTIVA, DecisionAudience.FOUNDERS),
        defaultOptions = listOf("Aprobar", "Rechazar"),
        defaultMinQuorumPercent = 50
    )
)

data class CustomClubRole(
    val id: String,
    val groupId: String,
    val title: String,
    val badgeIcon: String = "shield",
    val canCreateRide: Boolean = false,
    val canModerate: Boolean = false
)

data class ClubDecision(
    val id: String,
    val groupId: String,
    val title: String,
    val description: String = "",
    val category: DecisionCategory = DecisionCategory.GENERAL,
    val options: List<String> = listOf("A favor", "En contra", "Abstención"),
    val allowedAudiences: Set<DecisionAudience> = setOf(DecisionAudience.DIRECTIVA),
    val minQuorumPercent: Int = 0,
    val state: String = "open",
    val hasVoted: Boolean = false,
    val totalVotes: Int = 0,
    val eligibleCount: Int = 0,
    val quorumMet: Boolean = true,
    val tallies: List<Int>? = null,
    val winningOptionIndex: Int? = null,
    val closesAt: String = "",
    val closedAt: String? = null
) {
    val isOpen: Boolean get() = state.equals("open", ignoreCase = true)
}
