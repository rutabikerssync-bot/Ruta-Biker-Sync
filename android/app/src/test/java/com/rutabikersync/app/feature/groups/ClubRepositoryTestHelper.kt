package com.rutabikersync.app.feature.groups

/**
 * Helper de pruebas exclusivamente para Phase1RulesTest.
 * Aislado en src/test para que main/java no contenga funciones ni datos demo ficticios.
 */
fun ClubRepository.loadDemoDataForTesting() {
    val founder = UserProfileModel(
        id = "u-founder-1",
        email = "comandante@rutabikersync.mx",
        displayName = "Comandante Águila",
        countryCode = "MX",
        accountType = AccountType.LEADER,
        bikeModel = "BMW R1250GS Adventure",
        mapColorHex = "#FFB300",
        validRides = 14,
        hasFounderFlame = true,
        badges = listOf("founder", "first_ride", "500_km")
    )
    _currentUser.value = founder

    val demoUsers = listOf(
        founder,
        UserProfileModel("u-leader-2", "lobo@rbs.mx", "Lobo Norteño", "MX", AccountType.LEADER, "Triumph Tiger 900", "#00E676", 28, false, listOf("500_km")),
        UserProfileModel("u-leader-3", "halcon@rbs.mx", "Halcón Nocturno", "MX", AccountType.LEADER, "Yamaha Tenere 700", "#2979FF", 12, false, listOf("night_ride")),
        UserProfileModel("u-leader-4", "coyote@rbs.mx", "Coyote Sierra", "MX", AccountType.LEADER, "KTM 890 Adventure", "#FF9100", 8, false, emptyList()),
        UserProfileModel("u-member-1", "rayo@rbs.mx", "Rayo Azteca", "MX", AccountType.MEMBER, "Honda Africa Twin", "#00E5FF", 19, false, listOf("first_ride")),
        UserProfileModel("u-member-2", "sombra@rbs.mx", "Sombra Vial", "MX", AccountType.MEMBER, "Suzuki V-Strom 650", "#FF4081", 6, false, emptyList()),
        UserProfileModel("u-member-3", "toro@rbs.mx", "Toro Bravo", "MX", AccountType.MEMBER, "Harley Pan America", "#76FF03", 32, false, listOf("500_km")),
        UserProfileModel("u-member-4", "centella@rbs.mx", "Centella Sur", "MX", AccountType.MEMBER, "Ducati Multistrada V4", "#FFEA00", 4, false, emptyList()),
        UserProfileModel("u-prospect-1", "novato@rbs.mx", "Puma Joven", "MX", AccountType.MEMBER, "Kawasaki Versys 650", "#B388FF", 1, false, emptyList()),
        UserProfileModel("u-guest-1", "nomada@rbs.mx", "Nómada Libre", "MX", AccountType.MEMBER, "Royal Enfield Himalayan", "#1DE9B6", 11, false, listOf("3_groups"))
    )
    directoryUsers.clear()
    demoUsers.forEach { directoryUsers[it.id] = it }

    val group1 = ClubGroupModel(
        id = "grp-halcones-mx",
        countryCode = "MX",
        name = "Halcones del Asfalto",
        nameNormalized = ClubRulesEngine.normalizeGroupName("Halcones del Asfalto"),
        logoPhashHex = "a5a5a5a5a5a5a5a5",
        principalLeaderId = founder.id,
        leaderIds = listOf(founder.id, "u-leader-2", "u-leader-3"),
        tier = ClubTier.MC,
        stateRegion = "CDMX",
        foundationDate = "2012-04-18",
        sponsorGroupId = "grp-aguilas-mx",
        sponsorGroupName = "Águilas Ancestrales MC",
        sponsoredClubsCount = 4,
        members = listOf(
            ClubMemberModel(founder.id, founder.displayName, founder.bikeModel, MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 14),
            ClubMemberModel("u-leader-2", "Lobo Norteño", "Triumph Tiger 900", MemberStatusKind.FULL, ClubRole.VICE_PRESIDENT, isLeader = true, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 28),
            ClubMemberModel("u-leader-3", "Halcón Nocturno", "Yamaha Tenere 700", MemberStatusKind.FULL, ClubRole.ROAD_CAPTAIN, isLeader = true, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 12),
            ClubMemberModel("u-member-1", "Rayo Azteca", "Honda Africa Twin", MemberStatusKind.FULL, ClubRole.SERGEANT_AT_ARMS, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 19),
            ClubMemberModel("u-member-2", "Sombra Vial", "Suzuki V-Strom 650", MemberStatusKind.FULL, ClubRole.SECRETARY, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 6),
            ClubMemberModel("u-member-3", "Toro Bravo", "Harley Pan America", MemberStatusKind.FULL, ClubRole.TREASURER, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.YELLOW, validRides = 32),
            ClubMemberModel("u-member-4", "Centella Sur", "Ducati Multistrada V4", MemberStatusKind.FULL, ClubRole.ROAD_MARSHAL, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.RED, validRides = 4),
            ClubMemberModel("u-prospect-1", "Puma Joven", "Kawasaki Versys 650", MemberStatusKind.PROSPECT, null, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 1),
            ClubMemberModel("u-guest-1", "Nómada Libre", "Royal Enfield Himalayan", MemberStatusKind.GUEST, null, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 11)
        ),
        openVotes = listOf(
            LeaderVoteModel(
                id = "vote-demo-1",
                groupId = "grp-halcones-mx",
                proposal = "remove_member",
                targetLabel = "Centella Sur",
                approvals = setOf(founder.id),
                rejections = emptySet(),
                requiredVotes = 2,
                state = "open"
            )
        )
    )

    val groupAguilas = ClubGroupModel(
        id = "grp-aguilas-mx",
        countryCode = "MX",
        name = "Águilas Ancestrales MC",
        nameNormalized = ClubRulesEngine.normalizeGroupName("Águilas Ancestrales MC"),
        logoPhashHex = "ffff0000ffff0000",
        principalLeaderId = "u-leader-2",
        leaderIds = listOf("u-leader-2"),
        tier = ClubTier.MC,
        stateRegion = "Jalisco",
        foundationDate = "1998-03-12",
        sponsoredClubsCount = 9,
        members = listOf(
            ClubMemberModel("u-leader-2", "Lobo Norteño", "Triumph Tiger 900", MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 48)
        )
    )

    val group2 = ClubGroupModel(
        id = "grp-coyotes-mx",
        countryCode = "MX",
        name = "Coyotes de la Sierra",
        nameNormalized = ClubRulesEngine.normalizeGroupName("Coyotes de la Sierra"),
        logoPhashHex = "00ff00ff00ff00ff",
        principalLeaderId = "u-leader-2",
        leaderIds = listOf("u-leader-2"),
        tier = ClubTier.MG,
        stateRegion = "Nuevo León",
        foundationDate = "2021-02-10",
        sponsorGroupId = "grp-halcones-mx",
        sponsorGroupName = "Halcones del Asfalto",
        sponsoredClubsCount = 0,
        members = listOf(
            ClubMemberModel("u-leader-2", "Lobo Norteño", "Triumph Tiger 900", MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 28),
            ClubMemberModel("u-guest-1", "Nómada Libre", "Royal Enfield Himalayan", MemberStatusKind.GUEST, null, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 11),
            ClubMemberModel(founder.id, founder.displayName, founder.bikeModel, MemberStatusKind.GUEST, null, isLeader = false, isFounder = false, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 14)
        )
    )

    val groupCentauros = ClubGroupModel(
        id = "grp-centauros-ve",
        countryCode = "VE",
        name = "Centauros de Acero MC",
        nameNormalized = ClubRulesEngine.normalizeGroupName("Centauros de Acero MC"),
        logoPhashHex = "1122334455667788",
        principalLeaderId = "u-leader-3",
        leaderIds = listOf("u-leader-3"),
        tier = ClubTier.MC,
        stateRegion = "Distrito Capital",
        foundationDate = "2002-08-24",
        sponsoredClubsCount = 8,
        members = listOf(
            ClubMemberModel("u-leader-3", "Halcón Nocturno", "Yamaha Tenere 700", MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 35)
        )
    )

    val groupRutaLibre = ClubGroupModel(
        id = "grp-ruta-libre-ve",
        countryCode = "VE",
        name = "Ruta Libre RC",
        nameNormalized = ClubRulesEngine.normalizeGroupName("Ruta Libre RC"),
        logoPhashHex = "9988776655443322",
        principalLeaderId = "u-member-1",
        leaderIds = listOf("u-member-1"),
        tier = ClubTier.RC,
        stateRegion = "Miranda",
        foundationDate = "2023-01-15",
        sponsorGroupId = null,
        sponsorGroupName = null,
        sponsoredClubsCount = 0,
        members = listOf(
            ClubMemberModel("u-member-1", "Rayo Azteca", "Honda Africa Twin", MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 19)
        )
    )

    val groupExtCarabobo = ClubGroupModel(
        id = "grp-centauros-ext-carabobo",
        countryCode = "VE",
        name = "Centauros de Acero - Cap. Carabobo",
        nameNormalized = ClubRulesEngine.normalizeGroupName("Centauros de Acero - Cap. Carabobo"),
        logoPhashHex = "1122334455667788",
        principalLeaderId = "u-member-2",
        leaderIds = listOf("u-member-2"),
        tier = ClubTier.MC,
        stateRegion = "Carabobo",
        foundationDate = "2024-06-10",
        isExtension = true,
        parentGroupId = "grp-centauros-ve",
        parentGroupName = "Centauros de Acero MC",
        members = listOf(
            ClubMemberModel("u-member-2", "Sombra Vial", "Suzuki V-Strom 650", MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 12)
        )
    )

    val group3 = ClubGroupModel(
        id = "grp-condores-co",
        countryCode = "CO",
        name = "Cóndores Andinos MC",
        nameNormalized = ClubRulesEngine.normalizeGroupName("Cóndores Andinos MC"),
        logoPhashHex = "a5a5a5a5a5a5a5a5",
        principalLeaderId = "u-leader-4",
        leaderIds = listOf("u-leader-4"),
        tier = ClubTier.MC,
        stateRegion = "Bogotá D.C.",
        foundationDate = "2005-11-20",
        sponsoredClubsCount = 5,
        members = listOf(
            ClubMemberModel("u-leader-4", "Coyote Sierra", "KTM 890 Adventure", MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.GREEN, validRides = 22)
        )
    )

    val groupNomadasGang = ClubGroupModel(
        id = "grp-nomadas-co",
        countryCode = "CO",
        name = "Espíritus Nómadas Gang",
        nameNormalized = ClubRulesEngine.normalizeGroupName("Espíritus Nómadas Gang"),
        logoPhashHex = "3344556677889900",
        principalLeaderId = "u-member-3",
        leaderIds = listOf("u-member-3"),
        tier = ClubTier.MF,
        stateRegion = "Antioquia",
        foundationDate = "2019-07-04",
        sponsorGroupId = null,
        sponsorGroupName = null,
        sponsoredClubsCount = 0,
        members = listOf(
            ClubMemberModel("u-member-3", "Toro Bravo", "Harley Pan America", MemberStatusKind.FULL, ClubRole.PRESIDENT, isLeader = true, isFounder = true, paymentSemaphore = PaymentSemaphore.YELLOW, validRides = 32)
        )
    )

    _groups.value = listOf(
        group1,
        groupAguilas,
        group2,
        groupCentauros,
        groupRutaLibre,
        groupExtCarabobo,
        group3,
        groupNomadasGang
    )
    _pendingInvitations.value = listOf(
        ClubInvitationModel(
            id = "inv-demo-1",
            groupId = "grp-coyotes-mx",
            groupName = "Titanes del Pacífico",
            countryCode = "MX",
            invitedUserId = founder.id,
            statusKind = MemberStatusKind.GUEST,
            monthlyCostUsd = 0.0,
            state = "pending"
        )
    )

    _sponsorshipRequests.value = listOf(
        SponsorshipRequestModel(
            id = "req-demo-1",
            requestingClubId = "grp-coyotes-mx",
            requestingClubName = "Coyotes de la Sierra",
            requestingClubTier = ClubTier.MG,
            requestingClubCountry = "MX",
            requestingClubState = "Nuevo León",
            requestingPresidentName = "Lobo Norteño",
            requestingFoundationDate = "2021-02-10",
            targetSponsorClubId = "grp-halcones-mx",
            targetSponsorClubName = "Halcones del Asfalto",
            requestedAtDate = "2026-10-25",
            message = "Comandante Águila, solicitamos formalmente el respaldo y padrinazgo oficial de Halcones del Asfalto para rodar bajo su linaje y respeto en las rutas."
        )
    )

    _usurpationReports.value = listOf(
        UsurpationReport(
            id = "rep-demo-1",
            reportingClubId = "grp-centauros-ve",
            reportingClubName = "Centauros de Acero MC",
            reportingPresidentName = "Halcón Nocturno",
            reportedTargetName = "Falsos Centauros del Lago (Zulia)",
            reportedClubId = null,
            countryCode = "VE",
            stateRegion = "Zulia",
            infringement = UsurpationInfringement.UNAUTHORIZED_EXTENSION,
            description = "Grupo en Maracaibo usando parches y nombre de Centauros de Acero MC sin autorización del club ni del Consejo Nacional.",
            evidenceNotes = "Fotografías de chalecos y convocatoria en redes sociales.",
            reportedAtDate = "2026-10-27",
            status = "EN_REVISION"
        )
    )
}
