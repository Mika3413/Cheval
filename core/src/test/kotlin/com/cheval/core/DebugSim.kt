package com.cheval.core

/** Diagnostic : simule un domaine géré par son équipe et affiche le journal. Arguments : graine, années. */
object DebugSim {
    @JvmStatic
    fun main(args: Array<String>) {
        val seed = args.getOrNull(0)?.toLongOrNull() ?: 1L
        val years = args.getOrNull(1)?.toIntOrNull() ?: 1
        val g = Game(seed)
        g.newGameSetup(withStaff = true)
        g.takeStarter(g.starterChoices()[0])
        g.money = 300000
        g.build(BuildingType.CARRIERE)
        g.candidates.firstOrNull { it.role == Role.SOIGNEUR }?.let { g.hire(it) }
        g.candidates.firstOrNull { it.role == Role.CAVALIER }?.let { g.hire(it) }
        repeat(3) { g.buy(g.market.first()) }
        for (h in g.owned()) { g.vaccinate(h); h.plan = h.bestDiscipline() }
        repeat(365 * years) {
            if (g.daysOf(Item.FOIN) < 20) g.buyStock(Item.FOIN, 10)
            if (g.daysOf(Item.PAILLE) < 20) g.buyStock(Item.PAILLE, 4)
            if (g.daysOf(Item.GRANULES) < 20) g.buyStock(Item.GRANULES, 4)
            for (h in g.owned()) if (h.ailments.any { !it.treated }) g.callVet(h)
            g.advance(24.0)
        }
        g.messages.reversed().forEach { println("${Cal.formatShort(it.day)} ${it.hour}h [${it.kind}] ${it.text}") }
        println("Argent : ${fmtMoney(g.money)}, réputation ${g.reputation}")
        for (h in g.owned()) println("${h.name} ${h.breed.label} ${h.coatName(g.day)} BCS ${fmt1(h.bcs)} moral ${h.morale.toInt()} forme ${h.fitness.toInt()} " +
            Discipline.values().joinToString { "${it.short} ${h.skill(it).toInt()}" })
    }
}
