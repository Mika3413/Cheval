package com.cheval.core
object Scratch {
    @JvmStatic fun main(args: Array<String>) {
        val g = Game(12); g.newGameSetup(); g.takeStarter(g.starterChoices()[0])
        g.money = 400000
        g.build(BuildingType.CARRIERE); g.build(BuildingType.ABREUVOIRS); g.build(BuildingType.ECURIE)
        g.candidates.firstOrNull { it.role == Role.SOIGNEUR }?.let { g.hire(it) }
        g.candidates.firstOrNull { it.role == Role.CAVALIER }?.let { g.hire(it) }
        repeat(4) { println(g.buy(g.market.first { l -> g.horse(l.horseId)!!.age(g.day) >= 4 }).msg) }
        for (h in g.owned()) { g.vaccinate(h); g.deworm(h); h.plan = h.bestDiscipline(); h.turnout = Turnout.JOUR }
        repeat(365 * 2) {
            if (g.daysOf(Item.FOIN) < 20) g.buyStock(Item.FOIN, 10)
            if (g.daysOf(Item.PAILLE) < 20) g.buyStock(Item.PAILLE, 4)
            if (g.daysOf(Item.GRANULES) < 20) g.buyStock(Item.GRANULES, 4)
            if (g.daysOf(Item.MINERAUX) < 10) g.buyStock(Item.MINERAUX, 1)
            for (h in g.owned()) {
                if (h.ailments.any { !it.treated }) g.callVet(h)
                if (h.vaccineDue(g.day) < 0) g.vaccinate(h)
                if (h.dewormDue(g.day) < 0) g.deworm(h)
                if (h.farrierDue(g.day) < 0) g.farrier(h, h.shod)
                if (h.dentistDue(g.day) < 0) g.dentist(h)
            }
            g.advance(24.0)
            if (g.day % 60 == 0) println("j${g.day} €${g.money} " + g.owned().joinToString { "${it.name.take(8)} bcs ${fmt1(it.bcs)} m${it.morale.toInt()} sat${it.satiety.toInt()} e${it.energy.toInt()} sk${(it.skills.maxOrNull()?:0f).toInt()}" })
        }
        g.messages.filter { it.kind != MsgKind.INFO }.take(40).forEach { println("  ${Cal.formatShort(it.day)} " + it.text.take(110)) }
        for (h in g.owned()) println("${h.name} mor=${h.morale} sat=${h.satiety} hyd=${h.hydration} rack=${h.hayRack} place=${h.place} pain=${h.pain} ail=${h.ailments.map{it.type}} stress=${h.stress} ration=${h.ration.hay}/${h.ration.feed} hh=${h.hungryHours} pers=${h.personality}")
    }
}
