package com.alexisgordr.cellcase.core.report

import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.Abstention
import com.alexisgordr.cellcase.core.analysis.DatasetCuts
import com.alexisgordr.cellcase.core.analysis.NotEvaluated
import com.alexisgordr.cellcase.core.analysis.PROFILE_RULES_VERSION
import com.alexisgordr.cellcase.core.analysis.Check
import com.alexisgordr.cellcase.core.analysis.DataGap
import com.alexisgordr.cellcase.core.analysis.ObservationGroup
import com.alexisgordr.cellcase.core.analysis.RelevanceReason
import com.alexisgordr.cellcase.core.analysis.SpoofingRelevance
import com.alexisgordr.cellcase.core.analysis.CriterionCode
import com.alexisgordr.cellcase.core.analysis.EvidenceFamily
import com.alexisgordr.cellcase.core.analysis.LteBands
import com.alexisgordr.cellcase.core.analysis.MeasureStatus
import com.alexisgordr.cellcase.core.analysis.PciPattern
import com.alexisgordr.cellcase.core.analysis.MERGE_RULES_VERSION
import com.alexisgordr.cellcase.core.analysis.RowMatch
import com.alexisgordr.cellcase.core.analysis.VersionRelation
import com.alexisgordr.cellcase.core.analysis.NO_VALUE
import com.alexisgordr.cellcase.core.analysis.ForensicVersionGroup
import com.alexisgordr.cellcase.core.analysis.ForensicVersions
import com.alexisgordr.cellcase.core.analysis.MeasurementAvailability
import com.alexisgordr.cellcase.core.analysis.PatternKind
import com.alexisgordr.cellcase.core.analysis.family
import com.alexisgordr.cellcase.core.casefile.DeviceSource
import com.alexisgordr.cellcase.core.analysis.CheckCode
import com.alexisgordr.cellcase.core.analysis.EPISODE_RULES_VERSION
import com.alexisgordr.cellcase.core.analysis.Episode
import com.alexisgordr.cellcase.core.analysis.EpisodeEngine
import com.alexisgordr.cellcase.core.analysis.HistoryAnalysis
import com.alexisgordr.cellcase.core.analysis.HistoryAnalyzer
import com.alexisgordr.cellcase.core.analysis.Level
import com.alexisgordr.cellcase.core.analysis.Priority
import com.alexisgordr.cellcase.core.analysis.Quality
import com.alexisgordr.cellcase.core.analysis.RULESET_VERSION
import com.alexisgordr.cellcase.core.analysis.Signal
import com.alexisgordr.cellcase.core.analysis.SignalSource
import com.alexisgordr.cellcase.core.importing.EntryRole
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.withParts
import com.alexisgordr.cellcase.core.importing.backupParts
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.IntegrityStatus
import com.alexisgordr.cellcase.core.importing.PeriodBasis
import com.alexisgordr.cellcase.core.importing.PeriodInfo
import com.alexisgordr.cellcase.core.importing.READERS_VERSION
import com.alexisgordr.cellcase.core.model.CellIdentity
import com.alexisgordr.cellcase.core.model.CollectorVerdict
import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.Provenance
import com.alexisgordr.cellcase.core.model.RadioTech
import com.alexisgordr.cellcase.core.model.Severity
import com.alexisgordr.cellcase.core.model.ValueState
import com.alexisgordr.cellcase.core.time.TimeResolution
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.Messages
import com.alexisgordr.cellcase.core.analysis.CROSS_RULES_VERSION
import com.alexisgordr.cellcase.core.analysis.CrossCheck
import com.alexisgordr.cellcase.core.analysis.CrossField
import com.alexisgordr.cellcase.core.analysis.CrossFinding
import com.alexisgordr.cellcase.core.analysis.CrossOutcome
import com.alexisgordr.cellcase.core.analysis.CrossResult
import com.alexisgordr.cellcase.core.analysis.CrossGroup
import com.alexisgordr.cellcase.core.analysis.CrossSummary
import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.RegionalFormat
import com.alexisgordr.cellcase.core.i18n.ReasonGlossary

/**
 * Plantillas deterministas del informe (D-018, D-044). Una sola estructura para todos los idiomas: los textos
 * salen del catálogo del idioma ([Messages]) y el formato regional de [RegionalFormat]. Las reglas, los cálculos y
 * la valoración vienen de [CaseAnalysis] y no dependen del idioma.
 */
open class ReportTextsImpl(final override val language: Language) : ReportTexts {
    /** Texto del catálogo del idioma del informe. */
    protected fun t(key: String, vararg args: Any?): String = Messages.format(language, key, args.toList())
    /** Texto pendiente (aviso de importación, conflicto…) presentado en el idioma del informe. */
    fun tr(m: Msg): String = m.render(language)

    private val fmt = RegionalFormat.of(language)
    private val es = language.locale
    private val LOCAL = fmt.LOCAL
    private val SHORT = fmt.SHORT
    private val DATE = fmt.DATE
    private val UTC = fmt.UTC

    override val pageLabel = t("report.pageLabel.pagina_page_de_total")
    override val tableLabel = t("report.tableLabel.tabla")
    override val figureLabel = t("report.figureLabel.figura")
    override val continuedLabel = t("report.continuedLabel")
    override val noticeLabel = t("report.noticeLabel")

    // ───────────────────────── formatos ─────────────────────────

    /** Recuentos con el separador de millares del idioma ([RegionalFormat.int]). */
    override fun int(v: Long): String = fmt.int(v)
    private fun int(v: Int) = int(v.toLong())
    private fun pct(part: Int, total: Int) = if (total == 0) "—" else String.format(es, if (language == Language.EN) "%.1f%%" else "%.1f %%", 100.0 * part / total)
    private fun dur(d: Duration): String {
        val s = d.seconds.coerceAtLeast(0)
        return when {
            s < 60 -> "${s} s"
            s < 3600 -> t("report.dur.min", s / 60) + if (s % 60 != 0L && s < 600) " ${s % 60} s" else ""
            s < 48 * 3600 -> t("report.dur.h_min", s / 3600, "%02d".format(s % 3600 / 60))
            else -> "${s / 86400} d ${s % 86400 / 3600} h"
        }
    }
    private fun local(t: LocalDateTime) = LOCAL.format(t)
    private fun utc(i: Instant) = UTC.format(i)
    private fun at(ctx: TextContext, i: Instant) = LOCAL.format(i.atZone(ctx.displayZone))
    private fun short(ctx: TextContext, i: Instant) = SHORT.format(i.atZone(ctx.displayZone))
    private fun zoneNote(ctx: TextContext) = if (ctx.case.zone.confirmed) t("report.zoneNote.hora_local_de_supuesto_declarado", ctx.case.zone.zoneId.id) else t("report.zoneNote.utc_zona_sin_confirmar")
    fun bytes(n: Long) = when {
        n < 1024 -> "${int(n)} B"
        n < 1024 * 1024 -> String.format(es, t("report.bytes.1f_kib"), n / 1024.0)
        else -> String.format(es, t("report.bytes.1f_mib"), n / (1024.0 * 1024.0))
    }
    private fun idText(ctx: TextContext, id: CellIdentity?) = id?.let { Privacy.identity(it, ctx.options.identifiers) } ?: t("report.idText.sin_identidad")
    private fun key(ctx: TextContext, k: String) = Privacy.identityKey(k, ctx.options.identifiers)
    private fun ref(p: Provenance) = p.fileName + (p.zipEntry?.let { "!${it}" } ?: "") + ":" + p.startLine
    private fun when_(o: HistoryObservation): String {
        val l = o.time.local?.let { local(it) } ?: "«${o.time.raw}»"
        return when (val r = o.time.resolution) {
            is TimeResolution.Exact -> "${l} (${utc(r.instant)})"
            is TimeResolution.Ambiguous -> t("report.when_.ambigua", l)
            is TimeResolution.Nonexistent -> t("report.when_.inexistente_en_la_zona_declarada", l)
            else -> l
        }
    }

    // ───────────────────────── etiquetas ─────────────────────────

    override fun banner(origin: DataOrigin): String? = when (origin) {
        DataOrigin.SYNTHETIC -> t("report.banner.datos_sinteticos_no_reales")
        DataOrigin.MIXED -> t("report.banner.contiene_datos_sinteticos")
        DataOrigin.UNDETERMINED -> t("report.banner.origen_de_los_datos_no")
        DataOrigin.DECLARED_REAL -> null
    }

    fun originText(o: DataOrigin) = when (o) {
        DataOrigin.DECLARED_REAL -> t("report.originText.reales_segun_declara_el_usuario")
        DataOrigin.SYNTHETIC -> t("report.originText.sinteticos_de_prueba")
        DataOrigin.MIXED -> t("report.originText.mixtos_contiene_datos_sinteticos_junto")
        DataOrigin.UNDETERMINED -> t("report.originText.no_determinado_el_usuario_no")
    }

    /** Nombre del formato de exportación en el idioma del informe. */
    fun kindLabel(k: ExportKind): String = t("kind." + k.name)

    fun stateLabel(s: ImportState) = when (s) {
        ImportState.IMPORTED -> t("report.stateLabel.importado")
        ImportState.UNRECOGNIZED -> t("report.stateLabel.archivo_no_reconocido")
        ImportState.DAMAGED_ZIP -> t("report.stateLabel.zip_danado")
        ImportState.ZIP_SECURITY -> t("report.stateLabel.zip_rechazado_por_seguridad")
        ImportState.INCOMPATIBLE -> t("report.stateLabel.contenido_incompatible")
        ImportState.NOT_IMPLEMENTED -> t("report.stateLabel.importador_no_implementado")
        ImportState.READ_ERROR -> t("report.stateLabel.error_al_leer")
        ImportState.TOO_LARGE -> t("report.stateLabel.archivo_demasiado_grande")
    }

    fun integrityLabel(r: ImportResult) = when (r.integrity?.status) {
        IntegrityStatus.VERIFIED -> t("report.integrityLabel.sha256sums_verificado_entradas", r.integrity.checked)
        IntegrityStatus.MISMATCH -> t("report.integrityLabel.sha256sums_no_coincide", r.integrity.mismatched.joinToString())
        IntegrityStatus.INCOMPLETE -> t("report.integrityLabel.sha256sums_incompleto")
        IntegrityStatus.MALFORMED -> t("report.integrityLabel.sha256sums_ilegible")
        IntegrityStatus.NOT_PROVIDED -> t("report.integrityLabel.el_formato_no_incluye_sha256sums")
        null -> if (r.kind == ExportKind.HISTORY || r.kind == ExportKind.SERVICE_STATE) t("report.integrityLabel.el_formato_no_incluye_sha256sums") else "—"
    }

    private fun periodText(ctx: TextContext, p: PeriodInfo?): String = periodText(ctx.displayZone, p)

    fun periodText(zone: java.time.ZoneId, p: PeriodInfo?): String {
        if (p == null) return "—"
        fun i(x: Instant?) = x?.let { SHORT.format(it.atZone(zone)) } ?: "?"
        return when (p.basis) {
            PeriodBasis.OBSERVATIONS_LOCAL -> t("report.i.hora_local_del_telefono", p.startText?.let { local(LocalDateTime.parse(it.replace(' ', 'T'))).dropLast(3) } ?: "?", p.endText?.let { local(LocalDateTime.parse(it.replace(' ', 'T'))).dropLast(3) } ?: "?")
            PeriodBasis.OBSERVATIONS_UTC -> "${i(p.start)} – ${i(p.end)}"
            PeriodBasis.AGGREGATE_LAST_SEEN -> t("report.i.ultimas_rutas_vistas_entre_y", i(p.start), i(p.end))
            PeriodBasis.AGGREGATE_FIRST_LAST -> t("report.i.primer_y_ultimo_registro_agregado", i(p.start), i(p.end))
            PeriodBasis.TRIPS -> t("report.i.viajes_entre_y", i(p.start), i(p.end))
            PeriodBasis.DAYS_LOCAL -> "${p.startDate?.let { DATE.format(it) }} – ${p.endDate?.let { DATE.format(it) }}"
        }
    }

    fun countsText(r: ImportResult): String = when (r.kind) {
        ExportKind.HISTORY -> t("report.countsText.filas", int(r.counts["rows"] ?: 0))
        ExportKind.SERVICE_STATE -> t("report.countsText.eventos", int(r.counts["events"] ?: 0))
        ExportKind.FORENSIC_CASE -> t("report.countsText.muestras_celdas", int(r.counts["samples"] ?: 0), int(r.counts["cells"] ?: 0))
        ExportKind.TOPOLOGY -> t("report.countsText.rutas_handovers_agregado", int(r.counts["routes"] ?: 0), int(r.counts["handovers"] ?: 0))
        ExportKind.GEOMETRY -> t("report.countsText.rutas_viajes", int(r.counts["edges"] ?: 0), int(r.counts["trips"] ?: 0))
        ExportKind.STABLE_SITE -> t("report.countsText.claves_de_sitio_episodios", int(r.counts["sites"] ?: 0), int(r.counts["episodes"] ?: 0))
        ExportKind.BACKUP -> t("report.countsText.backup", int(r.counts["parts"] ?: 0), int(r.counts["rows"] ?: 0))
        ExportKind.UNKNOWN -> "—"
    }

    /** Aviso de discrepancia (D-032): se describe, no se resuelve. */
    fun discrepancyText(d: com.alexisgordr.cellcase.core.casefile.Discrepancy): String {
        val v = d.values
        return when (d.code) {
            com.alexisgordr.cellcase.core.casefile.DiscrepancyCode.FILE_DEVICE_DIFFERS_FROM_DETECTED ->
                t("report.discrepancyText.se_declaro_que_las_capturas", v["detected"], v["file"], v["fileDevice"])
            com.alexisgordr.cellcase.core.casefile.DiscrepancyCode.FILE_DEVICE_DIFFERS_FROM_DECLARED ->
                t("report.discrepancyText.el_dispositivo_declarado_es_pero", v["declared"], v["file"], v["fileDevice"])
            com.alexisgordr.cellcase.core.casefile.DiscrepancyCode.FILES_DISAGREE ->
                t("report.discrepancyText.los_archivos_indican_dispositivos_distintos", v["devices"])
            com.alexisgordr.cellcase.core.casefile.DiscrepancyCode.DECLARED_OTHER_BUT_FILES_MATCH_DETECTED ->
                t("report.discrepancyText.se_declaro_otro_dispositivo_pero", v["file"], v["fileDevice"], v["detected"])
            com.alexisgordr.cellcase.core.casefile.DiscrepancyCode.ZONE_DIFFERS_FROM_CURRENT ->
                t("report.discrepancyText.la_zona_declarada_para_la", v["declared"], v["current"])
        }
    }

    /** Explicación de un indicio automático de datos de prueba (no decide el origen, D-028). */
    fun indicatorText(code: String): String = when {
        code == "FILENAME_SYNTHETIC" -> t("report.indicatorText.el_nombre_del_archivo_indica")
        code.startsWith("TEST_MCC_001") -> t("report.indicatorText.registro_s_con_mcc_001", code.substringAfter(':', "")).trim()
        code == "DEVICE_SYNTHETIC" -> t("report.indicatorText.el_dispositivo_del_caso_esta")
        code == "TEST_PLMN_00101" -> t("report.indicatorText.operador_00101_red_de_pruebas")
        else -> code
    }

    /** Resumen de una importación para la aplicación: mismas palabras que el informe. */
    fun importSummary(r: ImportResult, zone: java.time.ZoneId): List<String> {
        val out = ArrayList<String>()
        out += "${kindLabel(r.kind)} · ${stateLabel(r.state)}"
        if (!r.imported) r.messageText?.let { out += tr(it) }
        if (r.imported) {
            out += countsText(r)
            r.schemaText?.let { out += tr(it) }
            out += t("report.importSummary.periodo") + periodText(zone, r.period)
            out += integrityLabel(r)
        }
        val warnings = r.issues.filter { it.severity != Severity.INFO }
        if (warnings.isNotEmpty()) out += t("report.importSummary.aviso_s_o_error_es", int(warnings.size), int(r.issues.size - warnings.size))
        else if (r.issues.isNotEmpty()) out += t("report.importSummary.nota_s_de_alcance_o", int(r.issues.size))
        if (r.syntheticIndicators.isNotEmpty()) out += t("report.importSummary.indicios_de_datos_de_prueba") + r.syntheticIndicators.joinToString("; ") { indicatorText(it) } + "."
        // Copia de seguridad: una línea por exportación reconstruida (backup-format.md §5).
        r.backupParts.forEach { p ->
            out += "  › " + p.fileName.substringAfter(" › ") + ": " + kindLabel(p.kind) + " · " +
                (if (p.imported) countsText(p) else stateLabel(p.state) + (p.messageText?.let { " — " + tr(it) } ?: ""))
        }
        return out
    }

    // ───────────────────────── cabecera ─────────────────────────

    override fun title(ctx: TextContext) = (if (ctx.options.mode == ReportMode.EXPLAINED) t("report.title.informe_explicado") else t("report.title.informe_tecnico")) + " — ${ctx.case.name}"
    override fun runningHeader(ctx: TextContext) = t("report.runningHeader.cellcase_expediente", ctx.case.name) + if (ctx.options.mode == ReportMode.EXPLAINED) t("report.runningHeader.explicado") else t("report.runningHeader.tecnico_completo")
    override fun runningFooter(ctx: TextContext) =
        t("report.runningFooter.generado_cellcase", utc(ctx.options.generatedAt), ctx.options.appVersion, RULESET_VERSION, EPISODE_RULES_VERSION)
    override fun pdfSubject(ctx: TextContext) = t("report.pdfSubject.informe_cellcase_del_expediente", ctx.case.name)

    private fun deviceText(ctx: TextContext): String {
        val c = ctx.case
        val decl = when (c.deviceSource) {
            DeviceSource.THIS_DEVICE -> t("report.deviceText.declarado_las_capturas_proceden_del", c.deviceLabel)
            DeviceSource.OTHER_DEVICE -> t("report.deviceText.declarado_por_el_usuario", c.deviceLabel)
            DeviceSource.NOT_DECLARED -> if (c.deviceLabel.isBlank() || c.deviceLabel == "no declarado") t("report.deviceText.no_declarado") else t("report.deviceText.declarado_por_el_usuario", c.deviceLabel)
        }
        return t("report.deviceText.no_verificado", decl)
    }

    override fun header(ctx: TextContext, a: CaseAnalysis): Block.Cover {
        val c = ctx.case
        val imported = ctx.results.count { it.imported }
        val hist = a.historyAnalysis?.period
        val firstLocal = hist?.first?.time?.local
        val lastLocal = hist?.last?.time?.local
        val periodDates = if (firstLocal != null && lastLocal != null) "${DATE.format(firstLocal)} – ${DATE.format(lastLocal)}" else null
        val period = if (firstLocal != null && lastLocal != null)
            t("report.header.historial_hora_local_del_telefono", DATE.format(firstLocal), DATE.format(lastLocal)) else "—"
        val fields = mutableListOf(
            t("report.label.expediente") to c.name + (c.description?.let { " — ${it}" } ?: ""),
            t("report.header.dispositivo_de_captura") to deviceText(ctx),
            t("report.header.origen_de_los_datos") to originText(c.origin),
            t("report.header.zona_horaria_de_captura") to "${c.zone.zoneId.id} — ${if (c.zone.confirmed) t("report.header.supuesto_declarado_y_confirmado") else t("report.header.sin_confirmar")}",
            t("report.label.alcance") to t("report.header.de_archivo_s_importado_s", int(imported), int(ctx.results.size), ctx.results.filter { it.imported }.map { kindLabel(it.kind) }.distinct().joinToString(", ").ifEmpty { t("report.header.ninguno") }),
            t("report.label.periodo") to period,
            t("report.label.generado") to t("report.header.cellcase", utc(ctx.options.generatedAt), ctx.options.appVersion, EPISODE_RULES_VERSION)
        )
        if (ctx.options.mode == ReportMode.FULL) {
            c.detected?.let { fields.add(2, t("report.header.telefono_de_analisis") to t("report.header.detectado_al_crear_el_expediente", it.deviceLabel)) }
            fields += t("report.header.lectores_y_reglas") to "${READERS_VERSION} · ${RULESET_VERSION} · ${EPISODE_RULES_VERSION} · ${CROSS_RULES_VERSION}"
            fields += t("report.header.idioma") to t("report.header.idioma_valor", language.locale.toLanguageTag())
        } else {
            fields.clear()
            fields += t("report.label.expediente") to c.name + (c.description?.let { " — ${it}" } ?: "")
            fields += t("report.header.telefono") to (if (c.deviceLabel.isBlank() || c.deviceLabel == "no declarado") t("report.deviceText.no_declarado") else c.deviceLabel) + t("report.header.declarado_por_quien_creo_el")
            fields += t("report.label.datos") to (periodDates ?: t("report.header.sin_historial")) + t("report.header.archivo_s_de_icdetector", int(imported))
            if (c.origin != DataOrigin.DECLARED_REAL) fields += t("report.header.origen_de_los_datos") to originText(c.origin)
            fields += t("report.label.generado") to utc(ctx.options.generatedAt)
            fields += t("report.header.idioma") to t("report.header.idioma_valor", language.locale.toLanguageTag())
        }
        return Block.Cover(
            kicker = t("report.header.exportaciones_de_icdetector") + if (ctx.options.mode == ReportMode.EXPLAINED) t("report.header.informe_explicado_para_no_especialistas") else t("report.header.informe_tecnico_completo"),
            title = title(ctx),
            subtitle = if (ctx.options.mode == ReportMode.EXPLAINED) t("report.header.que_dicen_los_datos_de")
                else t("report.header.que_registro_icdetector_que_comprueba"),
            fields = fields,
            notice = when (c.origin) {
                DataOrigin.SYNTHETIC -> t("report.header.datos_sinteticos_de_prueba_no")
                DataOrigin.MIXED -> t("report.header.el_expediente_mezcla_datos_sinteticos")
                DataOrigin.UNDETERMINED -> t("report.header.el_origen_de_los_datos")
                DataOrigin.DECLARED_REAL -> null
            },
            compact = ctx.options.mode == ReportMode.EXPLAINED
        )
    }

    private fun magnitude(r: RadioTech) = when (r) {
        RadioTech.LTE -> t("report.magnitude.getdbm_rsrp_en_android_actual")
        RadioTech.NR -> "SS-RSRP"
        RadioTech.UMTS -> t("report.magnitude.getdbm_rscp_rssi")
        RadioTech.GSM -> t("report.magnitude.getdbm_rssi")
        RadioTech.UNKNOWN -> t("report.magnitude.desconocida")
    }

    // ───────────────────────── vocabulario de incidencias ─────────────────────────

    fun priorityLabel(p: Priority) = when (p) {
        Priority.PRIORITY -> t("report.priorityLabel.revision_prioritaria")
        Priority.REVIEW -> t("report.priorityLabel.revisar")
        Priority.CONTEXT -> t("report.priorityLabel.contexto")
    }

    private fun sourceLabel(s: SignalSource) = when (s) {
        SignalSource.HISTORY_COLLECTOR -> t("report.sourceLabel.historial")
        SignalSource.FORENSIC_CASE -> t("report.sourceLabel.caso_forense")
        SignalSource.SERVICE_STATE -> t("report.sourceLabel.radio")
        SignalSource.STABLE_SITE_SHADOW -> t("report.sourceLabel.stable_site")
    }

    /** Nombre propio de CellCase para el patrón (nunca adopta «forzado» ni otras valoraciones de ICdetector). */
    fun patternLabel(k: PatternKind, e: Episode? = null): String = when (k) {
        PatternKind.BAND_CHANGE -> if (e != null && techChanges(e).isNotEmpty()) t("report.patternLabel.cambio_de_tecnologia") else t("report.patternLabel.cambio_de_banda_lte")
        PatternKind.RF_IDENTITY -> t("report.patternLabel.pci_alternante_en_la_misma")
        PatternKind.POWER_DEVIATION -> t("report.patternLabel.desviacion_de_potencia")
        PatternKind.TAC_DEVIATION -> t("report.patternLabel.desviacion_de_tac")
        PatternKind.TIMING_ADVANCE -> t("report.patternLabel.timing_advance_anomalo")
        PatternKind.TRANSITION -> t("report.patternLabel.transicion_entre_celdas_poco_habitual")
        PatternKind.ISOLATED_CELL -> t("report.patternLabel.celda_aislada")
        PatternKind.NETWORK_IDENTITY -> t("report.patternLabel.identificador_de_red_mcc_mnc")
        PatternKind.SPECTRUM -> t("report.patternLabel.frecuencia_o_vecinas_anomalas")
        PatternKind.CIPHERING -> t("report.patternLabel.cifrado")
        PatternKind.GEOGRAPHY -> t("report.patternLabel.coherencia_geografica")
        PatternKind.LATENCY -> t("report.patternLabel.latencia_y_rf")
        PatternKind.FORENSIC_CASE -> t("report.patternLabel.caso_forense_de_icdetector")
        PatternKind.STABLE_SITE_NOVELTY -> t("report.patternLabel.novedad_en_lugar_habitual")
        PatternKind.SERVICE_DEGRADED -> t("report.patternLabel.servicio_degradado")
        PatternKind.OTHER -> t("report.patternLabel.otro_motivo_de_icdetector")
    }

    @Suppress("UNCHECKED_CAST")
    private fun techChanges(e: Episode): List<String> = (e.measurement.values["techChanges"] as? List<String>).orEmpty().distinct()

    private fun cellsOf(ctx: TextContext, e: Episode) = e.identities.joinToString(", ") { ctx.cells.of(it.key) }.ifEmpty { t("report.cellsOf.sin_identidad_completa") }

    private fun hm(ctx: TextContext, i: Instant) = fmt.HM.format(i.atZone(ctx.displayZone))
    private fun hms(ctx: TextContext, i: Instant) = fmt.HMS.format(i.atZone(ctx.displayZone))

    /** «29/09 14:05–14:26» o «25/09 12:02» (una sola observación: sin intervalo). */
    private fun whenShort(ctx: TextContext, e: Episode): String =
        if (!e.durationKnown) short(ctx, e.start) else "${short(ctx, e.start)}–${hm(ctx, e.end)}"

    /** Bandas citadas por ICdetector en los motivos (B7→B8…), sin repetir. */
    private fun reportedBands(e: Episode) = e.signals.flatMap { it.reasons }.filter { it.bandFrom != null && it.bandTo != null }
        .map { "B${it.bandFrom}→B${it.bandTo}" }.distinct()

    private fun timesText(n: Int) = if (n == 1) t("report.timesText.1_vez") else t("report.timesText.veces", int(n))

    /** Lo que registró ICdetector, en palabras de CellCase (la cita literal va aparte). */
    private fun observedShort(ctx: TextContext, e: Episode): String {
        val col = e.signals.filter { it.source == SignalSource.HISTORY_COLLECTOR }
        val sub = if (col.isNotEmpty() && col.all { it.subthreshold }) t("report.observedShort.sub_umbral") else if (col.any { !it.subthreshold }) t("report.observedShort.alarma_de_icdetector") else null
        val detail = when (e.mainPattern) {
            PatternKind.BAND_CHANGE -> reportedBands(e).joinToString(", ").ifEmpty { null }
            PatternKind.POWER_DEVIATION -> e.signals.flatMap { it.reasons }.firstOrNull { it.db != null }?.let { t("report.observedShort.db", if (it.db!! > 0) "+" else "", it.db) }
            else -> null
        }
        val extra = e.sources.filter { it != SignalSource.HISTORY_COLLECTOR }.map { sourceLabel(it) }
        return listOfNotNull(t("report.observedShort.en", patternLabel(e.mainPattern, e), detail?.let { " (${it})" } ?: "", cellsOf(ctx, e)),
            timesText(e.moments) + (sub?.let { ", ${it}" } ?: ""),
            extra.takeIf { it.isNotEmpty() }?.let { t("report.observedShort.tambien_en", it.joinToString()) }).joinToString("; ")
    }

    @Suppress("UNCHECKED_CAST")
    private fun measurementText(ctx: TextContext, e: Episode, short: Boolean): String {
        val m = e.measurement
        val v = m.values
        return when (m.kind) {
            PatternKind.BAND_CHANGE -> when (m.status) {
                MeasureStatus.CONSISTENT -> {
                    val tr = (v["transitions"] as List<Map<String, Any>>).map { "B${it["from"]}→B${it["to"]} (${ctx.cells.of(it["fromKey"] as String)}→${ctx.cells.of(it["toKey"] as String)})" }.distinct()
                    if (short) t("report.measurementText.cambio_visible_en_las_filas", tr.joinToString(", "))
                    else t("report.measurementText.en_el_historial_la_fila", tr.joinToString(", "))
                }
                MeasureStatus.NOT_VISIBLE, MeasureStatus.PARTIAL -> if (short) t("report.measurementText.no_visible_en_las_filas") else t("report.measurementText.las_filas_anterior_y_posterior")
                MeasureStatus.NOT_EVALUABLE -> if (short) t("report.measurementText.no_comprobable_sin_fila_previa") else t("report.measurementText.no_hay_una_fila_anterior")
            }
            PatternKind.RF_IDENTITY -> {
                val series = (v["series"] as? List<EpisodeEngine.PciSeries>).orEmpty().filter { it.runs.size > 1 }
                fun seq(ser: EpisodeEngine.PciSeries) = t("report.seq.earfcn", ctx.cells.of(ser.key), ser.arfcn) +
                    ser.runs.joinToString(" → ") { t("report.seq.pci", it.pci, hms(ctx, it.from), if (it.to != it.from) "–" + hms(ctx, it.to) else "") } +
                    (if (ser.coincident > 0) t("report.seq.observacion_es_coincidente_s_misma", int(ser.coincident)) else "") +
                    (if (ser.conflicts.isNotEmpty()) t("report.seq.conflicto_temporal") + ser.conflicts.joinToString(", ") { c -> t("report.seq.pci_a_las", c.pcis.joinToString("/"), hms(ctx, c.time)) } + t("report.seq.misma_hora_orden_desconocido") else "")
                when (m.status) {
                    MeasureStatus.CONSISTENT -> {
                        val alt = series.filter { it.pattern == PciPattern.RETURN || it.pattern == PciPattern.REPEATED_ALTERNATION }
                        val what = if (alt.any { it.pattern == PciPattern.REPEATED_ALTERNATION }) t("report.seq.alternancia_repetida") else t("report.seq.vuelta_al_pci_anterior_aba")
                        if (short) t("report.seq.visible", what) else t("report.seq.en_la_misma_identidad_completa", what, alt.joinToString("; ") { seq(it) })
                    }
                    MeasureStatus.PARTIAL -> if (short) t("report.seq.cambio_de_pci_observado_no")
                        else t("report.seq.se_observa_un_unico_cambio", series.joinToString("; ") { seq(it) })
                    MeasureStatus.NOT_VISIBLE -> if (short) t("report.seq.un_solo_pci_en_las") else t("report.seq.las_filas_de_la_misma")
                    MeasureStatus.NOT_EVALUABLE -> if (v["conflictBlocks"] == true) {
                        val cs = (v["series"] as? List<EpisodeEngine.PciSeries>).orEmpty().filter { it.conflicts.isNotEmpty() }
                        if (short) t("report.seq.no_evaluable_pci_distintos_a")
                        else t("report.seq.hay_filas_de_la_misma", cs.joinToString("; ") { seq(it) })
                    } else if (short) t("report.seq.no_comprobable_sin_pci_earfcn") else t("report.seq.no_hay_filas_de_esa")
                }
            }
            PatternKind.POWER_DEVIATION -> {
                val ms = (v["measures"] as? List<Map<String, Int>>).orEmpty()
                when (m.status) {
                    MeasureStatus.NOT_EVALUABLE -> if (short) t("report.seq.sin_referencia_historica_suficiente") else t("report.seq.hay_menos_de_filas_de", EpisodeEngine.BASELINE_MIN_ROWS)
                    else -> ms.joinToString("; ") { t("report.seq.dbm_frente_a_mediana_dbm", it["dbm"], it["median"], int(it["n"]!!), if (it["diff"]!! > 0) "+" else "", it["diff"]) }.let {
                        if (short) (if (m.status == MeasureStatus.CONSISTENT) t("report.seq.diferencia_visible") else t("report.seq.diferencia_db", EpisodeEngine.POWER_DIFF_DB)) + it
                        else t("report.seq.con_la_referencia_de_la", it) + if (m.status == MeasureStatus.NOT_VISIBLE) t("report.seq.la_diferencia_es_menor_de", EpisodeEngine.POWER_DIFF_DB) else ""
                    }
                }
            }
            else -> if (short) t("report.seq.sin_comprobacion_propia_para_este") else t("report.seq.cellcase_no_tiene_una_comprobacion")
        }
    }

    private fun radioNoDegraded(e: Episode) = e.supports.none { it.code == CheckCode.RADIO_DEGRADED_IN_WINDOW } &&
        e.contradicts.any { it.code == CheckCode.RADIO_NO_DEGRADED_RECORDED }

    /** Lo que dice Radio de un episodio, sin el prefijo «Radio:» (para la frase común de la tabla de incidencias). */
    private fun radioBody(e: Episode): String {
        e.supports.firstOrNull { it.code == CheckCode.RADIO_DEGRADED_IN_WINDOW }?.let { return "${it.values["states"]}" }
        if (radioNoDegraded(e)) return t("report.radioBody.sin_eventos_degradados_registrados")
        return t("report.radioBody.sin_datos")
    }

    private fun radioText(e: Episode, short: Boolean): String? {
        e.supports.firstOrNull { it.code == CheckCode.RADIO_DEGRADED_IN_WINDOW }?.let { c ->
            return if (short) t("report.radioText.radio", c.values["states"]) else t("report.radioText.radio_registra_estado_s_degradado", int(c.values["count"] as Int), c.values["states"])
        }
        if (e.contradicts.any { it.code == CheckCode.RADIO_NO_DEGRADED_RECORDED }) return if (short) t("report.radioText.radio_sin_eventos_degradados_registrados")
            else t("report.radioText.no_se_registraron_eventos_de")
        if (e.notEvaluable.any { it.code == CheckCode.NO_RADIO_DATA }) return if (short) t("report.radioText.radio_sin_datos") else t("report.radioText.no_hay_datos_de_radio")
        return null
    }

    private fun recurrenceText(ctx: TextContext, e: Episode, short: Boolean): String = if (e.recurrenceDays >= 2) {
        val others = e.recurrenceEpisodes.joinToString { "E${it}" }
        if (short) t("report.recurrenceText.recurrente_dias", int(e.recurrenceDays)) + (if (others.isNotEmpty()) " (${others})" else "")
        else t("report.recurrenceText.el_mismo_patron_aparece_en", cellsOf(ctx, e), int(e.recurrenceDays)) + (if (others.isNotEmpty()) t("report.recurrenceText.tambien_en", others) else "") + t("report.recurrenceText.es_recurrencia_entre_dias_no")
    } else if (short) t("report.recurrenceText.sin_recurrencia_en_otros_dias") else t("report.recurrenceText.no_se_repite_en_otros")

    private fun persistenceText(ctx: TextContext, e: Episode): String = when {
        !e.durationKnown -> t("report.persistenceText.una_sola_observacion_a_las", hms(ctx, e.start))
        e.persistence == Level.HIGH -> t("report.persistenceText.observaciones_entre_y_se_repite", int(e.moments), hms(ctx, e.start), hms(ctx, e.end))
        else -> t("report.persistenceText.observaciones_entre_y_repeticion_breve", int(e.moments), hms(ctx, e.start), hms(ctx, e.end))
    }

    /** Interpretación: posibles explicaciones compatibles con el patrón concreto y lo medido. */
    private fun interpretation(e: Episode): List<String> {
        val out = ArrayList<String>()
        val tech = techChanges(e)
        when (e.mainPattern) {
            PatternKind.BAND_CHANGE -> {
                if (tech.isNotEmpty()) out += t("report.interpretation.hay_cambio_de_tecnologia_no", tech.joinToString())
                val toLow = e.signals.flatMap { it.reasons }.any { (it.freqToMhz ?: 9999) < 1000 }
                out += t("report.interpretation.un_cambio_de_banda_dentro") +
                    (if (toLow) t("report.interpretation.las_bandas_bajas_800_900") else ".")
                if (e.identities.size == 1 && e.moments > 1) out += t("report.interpretation.que_se_repita_en_la")
                if (e.measurement.status == MeasureStatus.NOT_VISIBLE) out += t("report.interpretation.que_las_filas_no_muestren")
            }
            PatternKind.RF_IDENTITY -> {
                out += t("report.interpretation.varias_portadoras_o_sectores_pueden")
                out += t("report.interpretation.tambien_puede_deberse_a_una")
                if (e.measurement.status == MeasureStatus.CONSISTENT) out += t("report.interpretation.la_alternancia_es_visible_en")
            }
            PatternKind.POWER_DEVIATION -> {
                out += t("report.interpretation.la_potencia_cambia_con_la")
                if (e.measurement.status == MeasureStatus.NOT_VISIBLE) out += t("report.interpretation.con_la_referencia_de_cellcase")
            }
            PatternKind.SERVICE_DEGRADED -> out += t("report.interpretation.los_estados_degradados_breves_pueden")
            PatternKind.TAC_DEVIATION -> out += t("report.interpretation.una_celda_en_el_borde")
            PatternKind.TIMING_ADVANCE -> out += t("report.interpretation.un_ta_bajo_es_normal")
            PatternKind.TRANSITION -> out += t("report.interpretation.un_cambio_de_celda_poco")
            else -> out += t("report.interpretation.las_heuristicas_de_icdetector_no")
        }
        return out
    }

    private fun whatItMeans(ctx: TextContext, e: Episode): List<String> {
        val out = ArrayList<String>()
        out += when (e.priority) {
            Priority.PRIORITY -> t("report.whatItMeans.revision_prioritaria") + priorityReason(e)
            Priority.REVIEW -> t("report.whatItMeans.merece_revision") + priorityReason(e)
            Priority.CONTEXT -> t("report.whatItMeans.observacion_de_contexto") + priorityReason(e)
        }
        out += interpretation(e)
        out += when (e.mainPattern) {
            PatternKind.BAND_CHANGE -> t("report.whatItMeans.no_permite_saber_que_motivo")
            PatternKind.RF_IDENTITY -> t("report.whatItMeans.no_permite_distinguir_entre_esas")
            PatternKind.POWER_DEVIATION -> t("report.whatItMeans.no_permite_atribuir_la_diferencia")
            else -> t("report.whatItMeans.no_permite_concluir_una_causa")
        }
        return out
    }

    /** Justificación de la prioridad con los criterios explícitos (sin porcentajes). */
    fun priorityReason(e: Episode): String {
        val c = e.criteria.map { it.code }.toSet()
        val parts = ArrayList<String>()
        if (CriterionCode.COLLECTOR_ALARM in c) parts += t("report.priorityReason.icdetector_lo_registro_como_alarma")
        if (CriterionCode.FORENSIC_ALARM_CASE in c) parts += t("report.priorityReason.icdetector_abrio_un_caso_forense")
        if (CriterionCode.FORENSIC_OBSERVATION_CASE in c) parts += t("report.priorityReason.hay_un_caso_forense_de")
        if (CriterionCode.SUBTHRESHOLD_ONLY in c) parts += t("report.priorityReason.icdetector_no_lo_registro_como")
        parts += when {
            CriterionCode.MEASURED_CONSISTENT in c -> t("report.priorityReason.el_comportamiento_se_comprueba_en")
            CriterionCode.MEASURED_PARTIAL in c -> t("report.priorityReason.las_mediciones_muestran_solo_una")
            CriterionCode.MEASURED_NOT_VISIBLE in c -> t("report.priorityReason.no_se_ve_en_las")
            CriterionCode.MEASUREMENTS_MISSING in c -> t("report.priorityReason.faltan_las_mediciones_necesarias_para")
            CriterionCode.MEASUREMENTS_CONFLICTING in c -> t("report.priorityReason.las_mediciones_tienen_pci_distintos")
            else -> t("report.priorityReason.cellcase_no_tiene_una_comprobacion")
        }
        if (CriterionCode.RECURRENT in c) parts += t("report.priorityReason.se_repite_en_dias", int(e.recurrenceDays))
        if (CriterionCode.SUSTAINED in c) parts += t("report.priorityReason.se_repite_dentro_del_episodio", int(e.moments))
        if (CriterionCode.SINGLE_OBSERVATION in c) parts += t("report.priorityReason.es_una_sola_observacion")
        if (CriterionCode.RADIO_COINCIDENCE_CONTEXT in c) parts += t("report.priorityReason.coincide_con_un_estado_de")
        if (CriterionCode.DEPENDENT_SOURCES_ONLY in c) parts += t("report.priorityReason.las_otras_fuentes_derivan_del")
        if (CriterionCode.QUALITY_LIMITED in c) parts += t("report.priorityReason.la_identidad_de_alguna_senal")
        return parts.joinToString("; ") + "."
    }

    private fun nextChecks(e: Episode): List<String> {
        val out = LinkedHashSet<String>()
        when (e.mainPattern) {
            PatternKind.BAND_CHANGE -> {
                out += t("report.nextChecks.comparar_la_banda_usada_en")
                if (techChanges(e).isNotEmpty()) out += t("report.nextChecks.revisar_por_que_el_telefono", techChanges(e).joinToString())
            }
            PatternKind.RF_IDENTITY -> {
                out += t("report.nextChecks.si_icdetector_abrio_un_caso")
                out += t("report.nextChecks.comprobar_si_el_mismo_pci")
            }
            PatternKind.POWER_DEVIATION -> out += t("report.nextChecks.comparar_con_capturas_en_el")
            PatternKind.SERVICE_DEGRADED -> out += t("report.nextChecks.comprobar_si_coincide_con_lugares")
            PatternKind.STABLE_SITE_NOVELTY -> out += t("report.nextChecks.comprobar_si_esa_celda_sigue")
            else -> {}
        }
        if (e.notEvaluable.any { it.code == CheckCode.NO_RADIO_DATA }) out += t("report.nextChecks.aportar_la_exportacion_de_radio")
        if (e.priority != Priority.CONTEXT) out += t("report.nextChecks.repetir_la_captura_en_el")
        return out.toList()
    }

    // ───────────────────────── resumen ─────────────────────────

    private fun reviewEpisodes(a: CaseAnalysis) = a.episodes.episodes.filter { it.priority != Priority.CONTEXT }
        .sortedWith(compareBy<Episode> { it.priority.ordinal }.thenBy { it.start })

    override fun summary(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block> {
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("report.summary.resumen"))
        val eps = a.episodes.episodes
        val pri = eps.count { it.priority == Priority.PRIORITY }; val rev = eps.count { it.priority == Priority.REVIEW }; val cx = eps.count { it.priority == Priority.CONTEXT }
        val alarms = eps.count { it.hasAlarm }
        val p = StringBuilder()
        p.append(when {
            eps.isEmpty() -> t("report.summary.los_datos_no_contienen_senales")
            pri == 0 && rev == 0 -> t("report.summary.no_hay_incidencias_que_merezcan", int(cx))
            pri == 0 -> t("report.summary.no_hay_incidencias_de_revision", int(rev), int(cx))
            else -> t("report.summary.incidencia_s_de_revision_prioritaria", int(pri), int(rev), int(cx))
        })
        if (eps.isNotEmpty()) p.append(if (alarms == 0) t("report.summary.icdetector_no_registro_ninguna_alarma") else t("report.summary.icdetector_registro_alarma_o_caso", int(alarms)))
        out += Block.Callout(if (a.assessment.overall == SpoofingRelevance.NO_INDICATION) CalloutKind.NOTE else CalloutKind.WARNING,
            t("report.summary.valoracion_para_la_pregunta_de"), overallStatement(a) +
                t("report.summary.el_interes_tecnico_prioridad_de"))
        if (eps.isNotEmpty() || a.episodes.unplacedCollectorRows.isNotEmpty()) p.append(" " + countsLine(a))
        out += Block.Paragraph(p.toString())
        val bullets = ArrayList<String>()
        reviewEpisodes(a).take(5).forEach { e -> bullets += "E${e.number} · ${whenShort(ctx, e)} · ${priorityLabel(e.priority)}: ${observedShort(ctx, e)}." }
        val ctxByPattern = eps.filter { it.priority == Priority.CONTEXT }.groupBy { patternLabel(it.mainPattern, it) }
        if (ctxByPattern.isNotEmpty()) bullets += t("report.summary.contexto") + ctxByPattern.entries.joinToString("; ") { (k, l) -> "${k.lowerFirst()} ${l.joinToString(", ") { "E${it.number}" }}" } + "."
        if (a.episodes.isolatedServiceSignals.isNotEmpty()) bullets += t("report.summary.radio_registra_estados_degradados_sin", serviceByType(a.episodes.isolatedServiceSignals))
        if (bullets.isNotEmpty()) out += Block.Bullets(bullets)
        out += Block.Paragraph(t("report.summary.con_los_criterios_implementados_no"), TextStyle.EMPHASIS)
        return out
    }

    // ───────────────────────── tabla de incidencias ─────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun registerCells(ctx: TextContext, a: CaseAnalysis) {
        val ordered = a.episodes.episodes.sortedWith(compareBy<Episode> { it.priority.ordinal }.thenBy { it.start })
        for (e in ordered) e.identities.forEach { ctx.cells.of(it.key) }
        for (e in ordered) (e.measurement.values["transitions"] as? List<Map<String, Any>>)?.forEach { ctx.cells.of(it["fromKey"] as String); ctx.cells.of(it["toKey"] as String) }
    }

    override fun incidents(ctx: TextContext, a: CaseAnalysis): List<Block> {
        val out = ArrayList<Block>()
        registerCells(ctx, a)
        val eps = a.episodes.episodes
        out += Block.Heading(1, t("report.incidents.incidencias"))
        if (eps.isEmpty()) { out += Block.Paragraph(t("report.incidents.no_hay_incidencias")); return out }
        val cols = listOf(Column(t("report.incidents.ep"), 0.5f), Column(t("report.incidents.fecha"), 1.45f), Column(t("report.incidents.observacion_icdetector"), 2.6f), Column(t("report.incidents.evidencia_cellcase"), 2.6f),
            Column(t("report.incidents.interpretacion"), 2f), Column(t("report.incidents.interes_tecnico"), 1.05f), Column(t("report.incidents.suplantacion"), 1.25f))
        val radioCommon = eps.map { radioText(it, true) }.distinct().singleOrNull()
        val many = eps.size > 15 && ctx.options.mode == ReportMode.EXPLAINED
        val shown = if (many) eps.filter { it.priority != Priority.CONTEXT } else eps
        val rows = shown.sortedBy { it.start }.map { e ->
            listOf("E${e.number}", whenShort(ctx, e), observedShort(ctx, e),
                listOfNotNull(measurementText(ctx, e, true), recurrenceText(ctx, e, true), radioText(e, true).takeIf { radioCommon == null }).joinToString("; "),
                shortInterpretation(e), priorityLabel(e.priority), relevanceShort(a.assessment.perEpisode.getValue(e.number).relevance))
        }.toMutableList()
        if (many) eps.filter { it.priority == Priority.CONTEXT }.groupBy { patternLabel(it.mainPattern, it) }.forEach { (k, l) ->
            rows += listOf("${int(l.size)}", "${short(ctx, l.minOf { it.start }).take(5)}–${short(ctx, l.maxOf { it.start }).take(5)}", t("report.incidents.agregado", k), "—", "—", t("report.priorityLabel.contexto"),
                l.map { relevanceShort(a.assessment.perEpisode.getValue(it.number).relevance) }.distinct().joinToString(" / "))
        }
        out += Block.Table(cols, rows, t("report.incidents.incidencias_en_orden_cronologico_observacion"))
        radioCommon?.let { eps.first().let { e0 -> out += Block.Paragraph(t("report.incidents.radio_en_todas_las_incidencias") + radioBody(e0) + if (radioNoDegraded(e0)) t("report.incidents.la_continuidad_del_registro_de") else ".", TextStyle.SMALL) } }
        val groups = a.episodes.groups.filter { it.episodes.size > 1 }
        val gl = groupLabels(a)
        if (groups.isNotEmpty()) out += Block.Paragraph(groups.joinToString(" ") { g ->
            t("report.incidents.grupo_son_proximos_en_el", gl[g.number], short(ctx, g.start), hm(ctx, g.end), g.episodes.joinToString(", ") { "E${it}" })
        }, TextStyle.SMALL)
        out += cellLegend(ctx)
        return out
    }

    private fun cellLegend(ctx: TextContext): Block {
        val e = ctx.cells.entries
        val half = (e.size + 1) / 2
        return Block.Table(
            listOf(Column(t("report.cellLegend.id"), 0.5f), Column(t("report.cellLegend.identidad_mcc_mnc_tac_cid"), 3f, mono = true, noWrap = true),
                Column(t("report.cellLegend.id"), 0.5f), Column(t("report.cellLegend.identidad"), 3f, mono = true, noWrap = true)),
            (0 until half).map { i -> listOf(e[i].first, key(ctx, e[i].second)) + (e.getOrNull(i + half)?.let { listOf(it.first, key(ctx, it.second)) } ?: listOf("", "")) },
            t("report.cellLegend.identificadores_cortos_de_celda_usados")
        )
    }

    private fun shortAction(e: Episode): String = when (e.mainPattern) {
        PatternKind.BAND_CHANGE -> if (techChanges(e).isNotEmpty()) t("report.shortAction.revisar_el_cambio_de_tecnologia") else t("report.shortAction.comparar_bandas_en_otros_dias")
        PatternKind.RF_IDENTITY -> t("report.shortAction.exportar_caso_forense_si_existe")
        PatternKind.POWER_DEVIATION -> t("report.shortAction.comparar_en_el_mismo_punto")
        PatternKind.SERVICE_DEGRADED -> t("report.shortAction.cotejar_con_zonas_de_cobertura")
        else -> if (e.priority == Priority.CONTEXT) t("report.shortAction.ninguna") else t("report.shortAction.repetir_la_captura")
    }

    private fun shortInterpretation(e: Episode): String = when (e.mainPattern) {
        PatternKind.BAND_CHANGE -> if (techChanges(e).isNotEmpty()) t("report.shortInterpretation.cambio_de_tecnologia_revisar_cobertura") else t("report.shortInterpretation.compatible_con_gestion_de_bandas")
        PatternKind.RF_IDENTITY -> t("report.shortInterpretation.varias_causas_posibles_no_distinguibles")
        PatternKind.POWER_DEVIATION -> t("report.shortInterpretation.compatible_con_cambio_de_posicion")
        PatternKind.SERVICE_DEGRADED -> t("report.shortInterpretation.compatible_con_poca_cobertura")
        else -> t("report.shortInterpretation.sin_interpretacion_concreta")
    } + if (e.measurement.status == MeasureStatus.NOT_VISIBLE) t("report.shortInterpretation.no_visible_en_datos") else ""

    // ───────────────────────── explicación de incidencias ─────────────────────────

    override fun explanations(ctx: TextContext, a: CaseAnalysis): List<Block> {
        val out = ArrayList<Block>()
        val full = ctx.options.mode == ReportMode.FULL
        out += Block.Heading(1, if (full) t("report.explanations.detalle_de_las_incidencias") else t("report.explanations.incidencias_que_merecen_revision"))
        val eps = if (full) a.episodes.episodes.sortedBy { it.start } else reviewEpisodes(a).sortedBy { it.start }
        if (eps.isEmpty()) {
            out += Block.Paragraph(t("report.explanations.ninguna_incidencia_cumple_los_criterios"))
            return out
        }
        if (full) { for (e in eps) out += episodeDetail(ctx, a, e); return out }
        // Resumido: una explicación por serie (misma celda y mismo patrón), con un gráfico por grupo temporal.
        val series = eps.groupBy { e -> e.identities.map { it.key }.sorted() to e.mainPattern }.values.sortedBy { l -> l.minOf { it.start } }
        val charted = HashSet<Pair<Int, PatternKind>>()
        for (l in series) {
            val e0 = l.first()
            val pr = l.minOf { it.priority.ordinal }.let { Priority.values()[it] }
            out += Block.Heading(2, t("report.explanations.en", patternLabel(e0.mainPattern, e0), cellsOf(ctx, e0), l.joinToString(", ") { "E${it.number} (${whenShort(ctx, it)})" }, priorityLabel(pr)))
            out += Block.Labeled(t("report.explanations.que_se_observo_registrado_por"), l.flatMap { e -> quotes(ctx, e, withDate = l.size > 1) }.let { q -> q.take(6) + if (q.size > 6) listOf(t("report.explanations.mas_informe_completo", int(q.size - 6))) else emptyList() })
            val ev = ArrayList<String>()
            l.forEach { e -> ev += (if (l.size > 1) "E${e.number}: " else "") + measurementText(ctx, e, false) + " " + persistenceText(ctx, e) }
            ev += recurrenceText(ctx, e0, false)
            l.flatMap { it.supports }.firstOrNull { it.code == CheckCode.RADIO_DEGRADED_IN_WINDOW }?.let { ev += radioText(l.first { e -> it in e.supports }, false)!! }
            out += Block.Labeled(t("report.explanations.que_evidencia_lo_respalda_comprobaciones"), ev)
            out += Block.Labeled(t("report.explanations.que_significa_y_que_no"), whatItMeans(ctx, l.maxByOrNull { it.moments }!!).let { m -> listOf(m.first().replaceFirst(Regex("^[^:]+: "), "${priorityLabel(pr)}: ")) + m.drop(1) })
            out += Block.Labeled(t("report.explanations.que_comprobar_despues"), l.flatMap { nextChecks(it) }.distinct())
            val main = l.maxByOrNull { it.moments }!!
            val key = main.group to main.mainPattern
            if (key !in charted) {
                val related = eps.filter { it.group == main.group && it.mainPattern == main.mainPattern }
                chartFor(ctx, a, related, compact = true)?.let { out += it }
                charted += key
            } else out += Block.Paragraph(t("report.explanations.el_grafico_de_la_serie", short(ctx, main.start)), TextStyle.SMALL)
        }
        return out
    }

    /**
     * Traducción explicativa de un motivo original de ICdetector, aparte de la cita (D-044). En español no se añade:
     * el original ya está en español. Nunca sustituye a la cita.
     */
    private fun gloss(original: String): String = ReasonGlossary.explain(original.take(160), language)?.let { t("report.quotes.gloss", it) } ?: ""

    private fun quotes(ctx: TextContext, e: Episode, withDate: Boolean): List<String> = e.collectorSignals.map { s ->
        (if (withDate) short(ctx, s.start) + ":" + "%02d".format(s.start.atZone(ctx.displayZone).second) else hms(ctx, s.start)) + " · " + when (s.source) {
            SignalSource.HISTORY_COLLECTOR -> t("report.quotes.historial", s.identity?.joinKey?.let { ctx.cells.of(it) } ?: "?", s.text.take(160)) + gloss(s.text)
            SignalSource.FORENSIC_CASE -> t("report.quotes.caso_forense_hasta", s.text, hms(ctx, s.end))
            SignalSource.STABLE_SITE_SHADOW -> t("report.quotes.stable_site", s.text) + gloss(s.text)
            SignalSource.SERVICE_STATE -> s.text
        }
    }

    /** Detalle de una incidencia en el informe completo. */
    private fun episodeDetail(ctx: TextContext, a: CaseAnalysis, e: Episode): List<Block> {
        val out = ArrayList<Block>()
        val gl = groupLabels(a)[e.group]
        out += Block.Heading(2, t("report.episodeDetail.e_en", e.number, whenShort(ctx, e), patternLabel(e.mainPattern, e), cellsOf(ctx, e), priorityLabel(e.priority)) + (gl?.let { " · ${it}" } ?: ""))
        val q = quotes(ctx, e, false)
        out += Block.Labeled(t("report.explanations.que_se_observo_registrado_por"), q.take(12) + if (q.size > 12) listOf(t("report.episodeDetail.mas_anexo_c", int(q.size - 12))) else emptyList())
        val ev = mutableListOf(measurementText(ctx, e, false), persistenceText(ctx, e), recurrenceText(ctx, e, false))
        radioText(e, true)?.let { ev += "${it}." }
        if (e.sources.count { it.family == EvidenceFamily.COLLECTOR } > 1) ev += t("report.episodeDetail.las_fuentes_del_episodio_derivan")
        ev += e.context.map { contextText(ctx, it, brief = true) } + e.contradicts.filter { it.code != CheckCode.RADIO_NO_DEGRADED_RECORDED }.map { contextText(ctx, it, brief = true) } +
            e.notEvaluable.filter { it.code != CheckCode.NO_RADIO_DATA }.map { contextText(ctx, it, brief = true) }
        out += Block.Labeled(t("report.explanations.que_evidencia_lo_respalda_comprobaciones"), ev.filter { it.isNotBlank() })
        out += Block.Labeled(t("report.explanations.que_significa_y_que_no"), whatItMeans(ctx, e))
        out += Block.Labeled(t("report.explanations.que_comprobar_despues"), nextChecks(e))
        chartFor(ctx, a, listOf(e), compact = true)?.let { out += it }
        fun servings(l: List<HistoryObservation>) = l.mapNotNull { it.identity.joinKey }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .take(3).joinToString(", ") { t("report.servings.filas", ctx.cells.known(it.key) ?: key(ctx, it.key), int(it.value)) }.ifEmpty { "—" }
        out += Block.Paragraph(t("report.servings.celdas_servidoras_30_min_antes", servings(e.before), servings(e.during), servings(e.after)) +
            t("report.servings.referencias") + e.signals.flatMap { it.refs }.distinct().take(6).joinToString("; ") { ref(it) } + ".", TextStyle.SMALL)
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun contextText(ctx: TextContext, c: Check, brief: Boolean = false): String {
        val k = (c.values["key"] as? String)?.let { ctx.cells.known(it) ?: key(ctx, it) } ?: ""
        return when (c.code) {
            CheckCode.VERIFICATION_STATUS -> t("report.contextText.verificacion_en_bases_publicas_para", k) + (c.values["counts"] as Map<String, Int>).entries.joinToString(", ") { t("report.contextText.filas", it.key, int(it.value)) } +
                if (brief) "." else t("report.contextText.verified_solo_indica_coincidencia_con")
            CheckCode.IDENTITY_RARE -> t("report.contextText.aparece_en_fila_s_y", k, int(c.values["rows"] as Int), int(c.values["days"] as Int))
            CheckCode.IDENTITY_ESTABLISHED -> t("report.contextText.es_habitual_dia_s_como", k, int(c.values["days"] as Int)) +
                ((c.values["siteDays"] as Int).takeIf { it > 0 }?.let { t("report.contextText.hasta_dia_s_en_una", int(it)) } ?: "") + "."
            CheckCode.TOPOLOGY_TRUSTED -> t("report.contextText.tiene_de_handovers_coherentes_en", k, int(c.values["trusted"] as Int), int(c.values["total"] as Int))
            CheckCode.GEOMETRY_KNOWN -> t("report.contextText.figura_como_known_on_route", k)
            CheckCode.TECH_CHANGE_OBSERVED -> t("report.contextText.cambio_de_tecnologia_observado_en", (c.values["changes"] as List<*>).distinct().joinToString())
            CheckCode.RADIO_DEGRADED_IN_WINDOW, CheckCode.RADIO_NO_DEGRADED_RECORDED, CheckCode.NO_RADIO_DATA -> ""
            CheckCode.NO_FORENSIC -> t("report.contextText.no_hay_caso_forense_que")
            CheckCode.NO_GNSS -> t("report.contextText.ninguna_fila_del_intervalo_tiene")
            CheckCode.TA_NOT_USABLE -> t("report.contextText.el_timing_advance_de_estas", (c.values["units"] as? String)?.ifBlank { null } ?: t("report.contextText.no_indicada"))
            CheckCode.NO_TOPOLOGY -> t("report.contextText.sin_topologia_no_se_puede")
            CheckCode.AMBIGUOUS_TIMES_EXCLUDED -> t("report.contextText.hay_filas_con_hora_ambigua")
        }
    }

    // ───────────────────────── informe explicado (D-036) ─────────────────────────

    /** Estado global en palabras para no especialistas. Lo usan el explicado y el técnico (mismas conclusiones). */
    fun overallStatement(a: CaseAnalysis): String = relevanceLabel(a.assessment.overall) + "."

    fun relevanceLabel(r: SpoofingRelevance) = when (r) {
        SpoofingRelevance.NO_INDICATION -> t("report.relevanceLabel.sin_indicios_suficientes_para_atribuir")
        SpoofingRelevance.NEEDS_MORE_DATA -> t("report.relevanceLabel.observaciones_que_necesitan_mas_datos")
        SpoofingRelevance.SPECIALIST -> t("report.relevanceLabel.indicios_que_requieren_investigacion_especializada")
    }

    /** Etiqueta corta por observación. */
    fun relevanceShort(r: SpoofingRelevance) = when (r) {
        SpoofingRelevance.NO_INDICATION -> t("report.relevanceShort.sin_indicios_suficientes")
        SpoofingRelevance.NEEDS_MORE_DATA -> t("report.relevanceShort.necesita_mas_datos")
        SpoofingRelevance.SPECIALIST -> t("report.relevanceShort.investigacion_especializada")
    }

    private fun layTitle(g: ObservationGroup, a: CaseAnalysis): String {
        if (g.kind == PatternKind.RF_IDENTITY) {
            val st = epsOf(a, g).map { it.measurement.status }
            return when {
                MeasureStatus.CONSISTENT in st -> t("report.layTitle.una_celda_alterno_entre_dos")
                MeasureStatus.PARTIAL in st -> t("report.layTitle.una_celda_cambio_de_identificador")
                else -> layTitle(g)
            }
        }
        if (g.kind == PatternKind.POWER_DEVIATION) {
            val db = epsOf(a, g).flatMap { e -> e.signals.flatMap { it.reasons } }.mapNotNull { it.db }
            if (db.isNotEmpty() && db.all { it > 0 }) return t("report.layTitle.senal_mucho_mas_fuerte_de")
            if (db.isNotEmpty() && db.all { it < 0 }) return t("report.layTitle.senal_mucho_mas_debil_de")
        }
        return layTitle(g)
    }

    private fun layTitle(g: ObservationGroup): String = when (g.kind) {
        PatternKind.BAND_CHANGE -> if (g.technologyDowngrade) t("report.layTitle.paso_de_4g_a_una") else t("report.layTitle.cambios_de_frecuencia_dentro_de")
        PatternKind.RF_IDENTITY -> t("report.layTitle.aviso_sobre_el_identificador_de")
        PatternKind.POWER_DEVIATION -> t("report.layTitle.senal_mucho_mas_fuerte_o")
        PatternKind.TAC_DEVIATION -> t("report.layTitle.cambio_inesperado_de_zona_de")
        PatternKind.TIMING_ADVANCE -> t("report.layTitle.distancia_estimada_a_la_celda")
        PatternKind.TRANSITION -> t("report.layTitle.cambio_de_celda_poco_habitual")
        PatternKind.ISOLATED_CELL -> t("report.layTitle.celda_sin_vecinas_conocidas")
        PatternKind.NETWORK_IDENTITY -> t("report.layTitle.identificador_de_operador_poco_habitual")
        PatternKind.SPECTRUM -> t("report.layTitle.frecuencia_o_celdas_vecinas_poco")
        PatternKind.CIPHERING -> t("report.layTitle.aviso_sobre_el_cifrado_de")
        PatternKind.GEOGRAPHY -> t("report.layTitle.celda_vista_en_lugares_poco")
        PatternKind.LATENCY -> t("report.layTitle.retrasos_y_senal_anomalos_a")
        PatternKind.FORENSIC_CASE -> t("report.layTitle.momento_guardado_por_icdetector_para")
        PatternKind.STABLE_SITE_NOVELTY -> t("report.layTitle.celda_nueva_en_un_lugar")
        PatternKind.SERVICE_DEGRADED -> t("report.layTitle.estados_de_servicio_degradado")
        PatternKind.OTHER -> t("report.layTitle.otro_aviso_de_icdetector")
    }

    private fun layShort(g: ObservationGroup): String = "${g.number}. " + when (g.kind) {
        PatternKind.BAND_CHANGE -> if (g.technologyDowngrade) t("report.layShort.paso_a_2g_3g") else t("report.layShort.cambios_de_frecuencia")
        PatternKind.RF_IDENTITY -> t("report.layShort.identificador_de_radio")
        PatternKind.POWER_DEVIATION -> t("report.layShort.senal_inusual")
        PatternKind.STABLE_SITE_NOVELTY -> t("report.layShort.celda_nueva")
        PatternKind.SERVICE_DEGRADED -> t("report.patternLabel.servicio_degradado")
        PatternKind.FORENSIC_CASE -> t("report.layShort.caso_guardado")
        else -> layTitle(g).substringBefore(" ").let { layTitle(g).take(22) }
    }

    private val LAY_DATE = fmt.LAY
    private fun layDays(days: List<LocalDate>): String = when (days.size) {
        0 -> t("report.layDays.fecha_desconocida")
        1 -> t("report.layDays.el", LAY_DATE.format(days[0]))
        2 -> t("report.layDays.el_y_el", LAY_DATE.format(days[0]), LAY_DATE.format(days[1]))
        else -> t("report.layDays.en_dias_entre_el_y", int(days.size), LAY_DATE.format(days.first()), LAY_DATE.format(days.last()))
    }
    private fun layTimes(n: Int) = if (n == 1) t("report.layTimes.una_vez") else t("report.timesText.veces", int(n))
    private fun cellsLay(ctx: TextContext, g: ObservationGroup) =
        if (g.cells.size == 1) t("report.cellsLay.una_celda", ctx.cells.of(g.cells[0])) else t("report.cellsLay.celdas", int(g.cells.size), g.cells.joinToString(", ") { ctx.cells.of(it) })

    private fun epsOf(a: CaseAnalysis, g: ObservationGroup) = a.episodes.episodes.filter { it.number in g.episodes }

    /** 1. Qué pasó, en lenguaje cotidiano. */
    private fun layWhat(ctx: TextContext, a: CaseAnalysis, g: ObservationGroup): List<String> {
        val eps = epsOf(a, g)
        val out = ArrayList<String>()
        out += when (g.kind) {
            PatternKind.BAND_CHANGE -> if (g.technologyDowngrade)
                t("report.layWhat.el_telefono_dejo_la_red", layDays(g.days).replaceFirstChar { it.uppercase() }, layTimes(g.moments))
            else t("report.layWhat.el_telefono_paso_a_usar", layDays(g.days).replaceFirstChar { it.uppercase() }, layTimes(g.moments), cellsLay(ctx, g))
            PatternKind.RF_IDENTITY -> {
                val st = eps.map { it.measurement.status }
                t("report.layWhat.icdetector_anoto_que_cambiaba_de", layDays(g.days).replaceFirstChar { it.uppercase() }, cellsLay(ctx, g)) + when {
                    MeasureStatus.CONSISTENT in st -> t("report.layWhat.en_los_registros_guardados_se")
                    MeasureStatus.PARTIAL in st -> t("report.layWhat.en_los_registros_guardados_solo")
                    MeasureStatus.NOT_VISIBLE in st -> t("report.layWhat.en_los_registros_guardados_se_2")
                    else -> t("report.layWhat.los_registros_guardados_no_permiten")
                }
            }
            PatternKind.POWER_DEVIATION -> t("report.layWhat.la_senal_de_fue_bastante", layDays(g.days).replaceFirstChar { it.uppercase() }, cellsLay(ctx, g), layTimes(g.moments))
            PatternKind.STABLE_SITE_NOVELTY -> t("report.layWhat.en_un_lugar_donde_el", layDays(g.days).replaceFirstChar { it.uppercase() })
            PatternKind.SERVICE_DEGRADED -> t("report.layWhat.el_telefono_se_quedo_sin", layTimes(g.moments), layDays(g.days))
            PatternKind.FORENSIC_CASE -> t("report.layWhat.icdetector_guardo_un_registro_detallado", layDays(g.days).replaceFirstChar { it.uppercase() })
            else -> t("report.layWhat.icdetector_registro_un_aviso_de", layDays(g.days).replaceFirstChar { it.uppercase() }, layTimes(g.moments), cellsLay(ctx, g))
        }
        if (g.kind == PatternKind.FORENSIC_CASE || eps.any { it.sources.contains(SignalSource.FORENSIC_CASE) }) {
            val origins = eps.flatMap { e -> e.signals.filter { it.source == SignalSource.FORENSIC_CASE } }
            out += if (origins.any { it.forensicOrigin == "ALARM" }) t("report.layWhat.icdetector_guardo_un_registro_detallado_2")
                else t("report.layWhat.icdetector_guardo_un_registro_detallado_3")
        }
        out += if (g.anyAlarm) t("report.layWhat.icdetector_lo_marco_como_alarma")
            else t("report.layWhat.no_alcanzo_el_umbral_de")
        val visible = eps.count { it.measurement.status == MeasureStatus.CONSISTENT }
        val notVisible = eps.count { it.measurement.status == MeasureStatus.NOT_VISIBLE }
        if (g.kind in EpisodeEngine.MEASURABLE && g.kind != PatternKind.RF_IDENTITY) out += when {
            visible == eps.size -> t("report.layWhat.cellcase_lo_comprobo_en_las")
            notVisible == eps.size -> t("report.layWhat.cellcase_no_lo_ve_en")
            visible > 0 -> t("report.layWhat.cellcase_lo_ve_en_las", int(visible), int(eps.size))
            else -> t("report.layWhat.cellcase_no_pudo_comprobarlo_en")
        }
        if (eps.any { it.sources.contains(SignalSource.SERVICE_STATE) }) out += t("report.layWhat.coincidio_con_un_estado_de")
        return out
    }

    /** 2. Qué explicaciones encajan. */
    private fun layExplanations(g: ObservationGroup): List<String> = when (g.kind) {
        PatternKind.BAND_CHANGE -> if (g.technologyDowngrade) listOf(
            t("report.layExplanations.falta_de_cobertura_4g_en"),
            t("report.layExplanations.mantenimiento_o_saturacion_de_la"),
            t("report.layExplanations.una_estacion_falsa_podria_provocar"))
        else listOf(
            t("report.layExplanations.las_operadoras_cambian_de_frecuencia"),
            t("report.layExplanations.las_frecuencias_bajas_llegan_mas"),
            t("report.layExplanations.que_se_repita_en_las"))
        PatternKind.RF_IDENTITY -> listOf(
            t("report.layExplanations.el_telefono_puede_leer_datos"),
            t("report.layExplanations.la_operadora_puede_haber_reconfigurado"),
            t("report.layExplanations.icdetector_vigila_este_patron_porque"))
        PatternKind.POWER_DEVIATION -> listOf(
            t("report.layExplanations.la_senal_cambia_mucho_segun"),
            t("report.layExplanations.la_referencia_de_lo_habitual"))
        PatternKind.STABLE_SITE_NOVELTY -> listOf(
            t("report.layExplanations.la_operadora_puede_haber_activado"),
            t("report.layExplanations.el_telefono_pudo_conectarse_a"))
        PatternKind.SERVICE_DEGRADED -> listOf(t("report.layExplanations.zonas_con_poca_cobertura_como"), t("report.layExplanations.reinicios_o_cambios_de_modo"))
        PatternKind.FORENSIC_CASE -> listOf(t("report.layExplanations.icdetector_guarda_estos_registros_cuando"))
        else -> listOf(t("report.layExplanations.las_comprobaciones_de_icdetector_no"),
            t("report.layExplanations.con_los_datos_disponibles_no"))
    }

    /**
     * Cruces entre fuentes que ayudan a entender una observación (vinculados a sus episodios), en frases sencillas.
     * Solo los que tienen frase propia; sin repetir la misma frase.
     */
    private fun layCross(a: CaseAnalysis, g: ObservationGroup): List<String> =
        a.cross?.findings.orEmpty().filter { f -> f.episodes.any { it in g.episodes } && crossLay(f) != null }
            .groupBy { it.check to it.sourceB }.values.map { l -> l.minBy { it.outcome.ordinal } }
            .sortedWith(compareBy({ it.outcome.ordinal }, { it.check.ordinal }, { it.sourceB.ordinal })).mapNotNull { crossLay(it) }.distinct().take(3)

    /** Perfil local de ICdetector en las filas de los episodios de una observación, en una frase sencilla (o null). */
    private fun layProfile(a: CaseAnalysis, g: ObservationGroup): String? {
        val p = a.localProfile ?: return null
        val eps = p.episodes.filter { it.episode in g.episodes }
        val evaluated = eps.sumOf { it.evaluated }
        if (evaluated == 0) return null
        val deviated = eps.sumOf { it.deviated }
        val abstained = eps.sumOf { it.abstained }
        return when {
            deviated > 0 -> t("profile.lay.deviated", int(deviated), profileLevel(eps.mapNotNull { it.extreme }.maxBy { com.alexisgordr.cellcase.core.model.ProfileEvaluation.LEVELS.indexOf(it.level) }.level))
            abstained == evaluated -> t("profile.lay.abstained")
            else -> t("profile.lay.usual")
        }
    }

    /** 3. Qué falta para aclararlo. */
    private fun layMissing(a: CaseAnalysis, g: ObservationGroup): List<String> {
        val out = ArrayList<String>()
        when (g.kind) {
            PatternKind.BAND_CHANGE -> if (g.technologyDowngrade) out += t("report.layMissing.saber_si_en_ese_lugar")
                else out += t("report.layMissing.nada_imprescindible_es_un_comportamiento")
            PatternKind.RF_IDENTITY -> out += t("report.layMissing.un_registro_detallado_de_icdetector")
            PatternKind.POWER_DEVIATION -> out += t("report.layMissing.mediciones_repetidas_en_el_mismo")
            PatternKind.STABLE_SITE_NOVELTY -> out += t("report.layMissing.ver_si_esa_celda_sigue")
            else -> out += t("report.layMissing.mas_capturas_en_el_mismo")
        }
        if (DataGap.NO_RADIO in a.assessment.dataGaps) out += t("report.layMissing.la_exportacion_de_radio_de")
        return out
    }

    /** 4. Una acción concreta y proporcionada. */
    private fun layAction(g: ObservationGroup): String = when {
        g.relevance == SpoofingRelevance.NO_INDICATION && g.kind == PatternKind.BAND_CHANGE -> t("report.layAction.estos_datos_no_justifican_una")
        g.relevance == SpoofingRelevance.NO_INDICATION -> t("report.layAction.estos_datos_no_justifican_una_2")
        g.kind == PatternKind.RF_IDENTITY || g.anyAlarm -> t("report.layAction.si_vuelves_a_ese_lugar")
        g.technologyDowngrade -> t("report.layAction.anota_donde_estabas_si_se")
        else -> t("report.layAction.repite_la_captura_en_el")
    }

    override fun explained(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block> {
        registerCells(ctx, a)
        val out = ArrayList<Block>()
        val asm = a.assessment
        val groups = asm.groups
        val isolatedRadio = a.episodes.isolatedServiceSignals.size

        // ── Respuestas ──
        out += Block.Heading(1, t("report.explained.que_permiten_concluir_los_datos"))
        out += Block.Callout(if (asm.overall == SpoofingRelevance.NO_INDICATION) CalloutKind.NOTE else CalloutKind.WARNING, t("report.explained.estado", relevanceLabel(asm.overall)),
            when {
                DataGap.NO_SIGNAL_SOURCE in asm.dataGaps -> t("report.explained.los_archivos_aportados_no_incluyen")
                groups.isEmpty() -> t("report.explained.icdetector_no_registro_ningun_aviso")
                asm.overall == SpoofingRelevance.NO_INDICATION -> t("report.explained.lo_registrado_puede_explicarse_por")
                else -> t("report.explained.ninguna_observacion_permite_atribuir_un") + groups.count { it.relevance == SpoofingRelevance.NEEDS_MORE_DATA }.let { n ->
                    if (n == 1) t("report.explained.una_observacion_no_se_puede") else t("report.explained.observaciones_no_se_pueden_aclarar", int(n)) }
            })
        out += Block.Paragraph(t("report.explained.una_estacion_base_falsa_a") +
            t("report.explained.este_informe_no_puede_confirmar"))
        if (DataGap.ZONE_NOT_CONFIRMED in asm.dataGaps || DataGap.UNPLACED_ROWS in asm.dataGaps)
            out += Block.Paragraph(t("report.explained.algunos_registros_no_se_pudieron"), TextStyle.SMALL)

        out += Block.Heading(1, t("report.explained.que_observaciones_merecen_atencion_y"))
        if (groups.isNotEmpty()) out += Block.Paragraph(t("report.explained.en_cifras") + countsLine(a).lowerFirst() +
            t("report.explained.un_registro_es_una_anotacion"), TextStyle.SMALL)
        if (groups.isEmpty()) out += Block.Paragraph(t("report.explained.ninguna") + if (isolatedRadio > 0) t("report.explained.solo_constan_estados_de_servicio", serviceByType(a.episodes.isolatedServiceSignals)) else "")
        else {
            val needs = groups.filter { it.relevance != SpoofingRelevance.NO_INDICATION }
            val calm = groups.filter { it.relevance == SpoofingRelevance.NO_INDICATION }
            val items = ArrayList<String>()
            needs.forEach { g -> items += t("report.explained.necesita_mas_datos", layTitle(g, a), layDays(g.days), whyAttention(g)) }
            calm.forEach { g -> items += t("report.explained.sin_indicios_suficientes", layTitle(g, a), layDays(g.days), layTimes(g.moments)) +
                (if (g.kind == PatternKind.BAND_CHANGE) t("report.explained.es_un_comportamiento_habitual_de") else t("report.explained.puede_tener_explicaciones_normales")) }
            if (isolatedRadio > 0) items += t("report.explained.ademas_radio_registro_estados_de", serviceByType(a.episodes.isolatedServiceSignals))
            out += Block.Bullets(items)
            val technical = a.episodes.episodes.filter { it.priority != Priority.CONTEXT && asm.perEpisode.getValue(it.number).relevance == SpoofingRelevance.NO_INDICATION }
            if (technical.isNotEmpty()) out += Block.Paragraph(t("report.explained.el_informe_tecnico_marca_para", technical.joinToString(", ") { "E${it.number}" }) +
                t("report.explained.es_interes_tecnico_no_un"), TextStyle.SMALL)
        }

        out += Block.Heading(1, t("report.explained.que_puedes_hacer_despues"))
        val actions = LinkedHashSet<String>()
        groups.filter { it.relevance != SpoofingRelevance.NO_INDICATION }.forEach { actions += "${layTitle(it, a)}: " + layAction(it).lowerFirst() }
        if (groups.any { it.relevance == SpoofingRelevance.NO_INDICATION })
            actions += (if (actions.isEmpty()) "" else t("report.explained.para_el_resto_de_observaciones")) .let { p -> if (p.isEmpty()) t("report.layAction.estos_datos_no_justifican_una") else p + t("report.explained.estos_datos_no_justifican_una") }
        if (DataGap.NO_SIGNAL_SOURCE in asm.dataGaps) actions += t("report.explained.exporta_desde_icdetector_el_historial")
        if (DataGap.ZONE_NOT_CONFIRMED in asm.dataGaps) actions += t("report.explained.confirma_en_el_expediente_la")
        if (DataGap.NO_RADIO in asm.dataGaps && groups.isNotEmpty()) actions += t("report.explained.anade_la_exportacion_de_radio")
        if (actions.isEmpty()) actions += t("report.explained.nada_en_particular_manten_icdetector")
        if (asm.overall == SpoofingRelevance.NEEDS_MORE_DATA) actions += t("report.explained.si_te_preocupa_una_situacion")
        out += Block.Bullets(actions.toList())

        // ── Observaciones explicadas ──
        if (groups.isNotEmpty()) {
            out += Block.Heading(1, t("report.explained.las_observaciones_una_a_una"))
            for (g in groups) {
                out += Block.Heading(2, "${g.number}. ${layTitle(g, a)} — ${relevanceShort(g.relevance)}")
                val crossLay = layCross(a, g)
                val profileLay = layProfile(a, g)
                if (g.relevance == SpoofingRelevance.NO_INDICATION) {
                    // Misma estructura en cuatro partes, en formato compacto: no necesita desarrollo.
                    out += Block.KeyValues(listOf(
                        t("report.explained.que_paso") to layWhat(ctx, a, g).joinToString(" "),
                        t("report.label.explicaciones") to layExplanations(g).joinToString(" "),
                        t("report.explained.que_falta") to layMissing(a, g).joinToString(" "),
                        t("report.explained.que_hacer") to layAction(g)
                    ) + (if (crossLay.isEmpty()) emptyList() else listOf(t("explained.cross.label") to crossLay.joinToString(" "))) +
                        (if (profileLay == null) emptyList() else listOf(t("profile.lay.label") to profileLay)))
                } else {
                    out += Block.Labeled(t("report.explained.que_paso"), layWhat(ctx, a, g))
                    out += Block.Labeled(t("report.explained.que_explicaciones_encajan"), layExplanations(g))
                    out += Block.Labeled(t("report.explained.que_falta_para_aclararlo"), layMissing(a, g))
                    out += Block.Labeled(t("report.explained.que_hacer"), listOf(layAction(g)))
                    if (crossLay.isNotEmpty()) out += Block.Labeled(t("explained.cross.label"), crossLay)
                    if (profileLay != null) out += Block.Labeled(t("profile.lay.label"), listOf(profileLay))
                }
                out += Block.Paragraph(t(if (crossLay.isEmpty()) "report.explained.detalle_tecnico_en_el_informe" else "explained.cross.ref", g.episodes.joinToString(", ") { "E${it}" }), TextStyle.SMALL)
            }
            whenChart(ctx, a)?.let { out += it }
        }

        // ── Límites ──
        out += Block.Heading(1, t("report.explained.lo_que_este_informe_no"))
        out += Block.Bullets(listOfNotNull(
            t("report.explained.no_puede_confirmar_ni_descartar"),
            t("report.explained.icdetector_anota_muchos_avisos_que"),
            t("report.explained.un_registro_aislado_no_dice"),
            t("report.explained.el_telefono_la_zona_horaria") + if (ctx.case.discrepancies.isNotEmpty()) t("report.explained.hay_avisos_de_discrepancia_vease") else "",
            a.localProfile?.takeIf { it.evaluated > 0 }?.let { t("profile.lay.limit") },
            availability.filter { it.status != Availability.AVAILABLE }.takeIf { it.isNotEmpty() }?.let { t("report.explained.faltan_datos_para_algunos_analisis", it.size) }
        ), TextStyle.SMALL)
        val imported = ctx.results.filter { it.imported }
        out += Block.Paragraph(t("report.explained.datos_analizados_archivo_s_de", int(imported.size), imported.map { kindLabel(it.kind).lowercase() }.distinct().joinToString(", ").ifEmpty { t("report.header.ninguno") }) +
            t("report.explained.las_celdas_se_nombran_c1", EPISODE_RULES_VERSION), TextStyle.SMALL)
        return out
    }

    /** Clave «sin valor» de un recuento ([NO_VALUE]) en el idioma del informe. */
    private fun noValue(k: String) = if (k == NO_VALUE) t("report.noValue") else k

    private fun fileOf(ctx: TextContext, importId: String) = ctx.results.flatMap { it.withParts() }.firstOrNull { it.importId == importId }?.fileName ?: importId

    /** Copia de seguridad: lo que declara el manifiesto, lo comprobado y de dónde salen las partes (backup-format.md). */
    private fun backupBlocks(ctx: TextContext, r: ImportResult): List<Block> {
        val info = (r.data as? ImportedData.Backup)?.info
        val rows = ArrayList<Pair<String, String>>()
        rows += t("backup.report.file") to r.fileName
        if (info != null) {
            rows += t("backup.report.app") to t("backup.report.app_value", info.appVersion ?: "?", info.schemaVersion ?: "?", info.format ?: "?")
            rows += t("backup.report.created") to (info.createdAt?.let { short(ctx, it) } ?: info.createdAtText ?: "—")
            rows += t("backup.report.database") to t(if (info.integrityVerified) "backup.report.db_verified" else "backup.report.db_mismatch",
                bytes(info.databaseBytes ?: 0), info.databaseSha256?.take(16) ?: "—")
            val tables = listOf("history", "profile_evaluations", "service_state_events", "cell_transitions", "mobility_trips", "site_cell_evidence", "site_shadow_triggers", "forensic_cases", "forensic_samples")
                .mapNotNull { n -> info.tables[n]?.let { "$n ${int(it)}" } }
            if (tables.isNotEmpty()) rows += t("backup.report.tables") to tables.joinToString(" · ")
            rows += t("backup.report.parts") to r.backupParts.joinToString("; ") { p -> kindLabel(p.kind) + (if (p.imported) "" else " (" + stateLabel(p.state).lowercase() + ")") }.ifEmpty { "—" }
            if (info.profileReferences.isNotEmpty()) rows += t("backup.report.profile_refs") to info.profileReferences.entries.joinToString(" · ") { "${profileMaturity(it.key)} ${int(it.value)}" }
        } else rows += t("report.networkContext.estado") to (r.messageText?.let { tr(it) } ?: stateLabel(r.state))
        return listOf(Block.Heading(2, t("backup.report.title")), Block.KeyValues(rows), Block.Paragraph(t("backup.report.note"), TextStyle.SMALL))
    }

    private fun profileMaturity(m: String) = when (m) { "LEARNING", "MATURE", "STALE" -> t("profile.maturity.$m"); else -> m }

    /** Explicación de la relación entre versiones (el análisis no produce texto: D-044). */
    private fun versionExplanation(g: ForensicVersionGroup): String = when (g.relation) {
        VersionRelation.SINGLE -> ""
        VersionRelation.EQUIVALENT -> t("report.versions.equivalentes")
        VersionRelation.EXTENDED -> t("report.versions.ampliada")
        VersionRelation.CONTRADICTORY ->
            if (g.conflicts.isNotEmpty() && g.conflicts.all { it.key == null && it.field in PROGRESS_FIELDS }) t("report.versions.metadatos_incompatibles")
            else t("report.versions.contradictorias")
        VersionRelation.DIVERGENT -> t("report.versions.divergentes")
    }

    private val PROGRESS_FIELDS = setOf("state", "confirmed", "highestPhase", "sampleCount", "updatedAt", "closedAt", "contentAfterClosure", "code")

    /** Nombre del campo o tabla de un conflicto. Los nombres de ICdetector se dejan tal cual. */
    private fun conflictField(f: String): String = when (f) {
        "samples" -> t("report.versions.f_muestras")
        "cells" -> t("report.versions.f_celdas")
        "heuristics" -> t("report.versions.f_diagnosticos")
        "capabilities" -> t("report.versions.f_capacidades")
        "terminal" -> t("report.versions.f_terminal")
        "contentAfterClosure" -> t("report.versions.f_contenido_tras_cierre")
        else -> f
    }

    /** Relación entre versiones de un caso forense, para la aplicación (mismas palabras que el informe). */
    fun versionRelationLabel(r: VersionRelation) = versionLabel(r)

    private fun versionLabel(r: VersionRelation) = when (r) {
        VersionRelation.SINGLE -> t("report.versionLabel.unica")
        VersionRelation.EQUIVALENT -> t("report.versionLabel.equivalentes")
        VersionRelation.EXTENDED -> t("report.versionLabel.ampliada_o_actualizada")
        VersionRelation.CONTRADICTORY -> t("report.versionLabel.contradictorias")
        VersionRelation.DIVERGENT -> t("report.versionLabel.divergentes")
    }

    /** Distingue registros (lo anotado por ICdetector), episodios (agrupación) y episodios comprobables (D-039). */
    fun countsLine(a: CaseAnalysis): String {
        val eps = a.episodes.episodes
        val records = eps.sumOf { e -> e.signals.count { it.source != SignalSource.SERVICE_STATE } } + a.episodes.unplacedCollectorRows.size
        val checkable = eps.count { it.measurement.availability == MeasurementAvailability.AVAILABLE }
        return t("report.countsLine.registro_s_de_icdetector_agrupados", int(records), int(eps.size), int(checkable)) +
            (if (a.episodes.unplacedCollectorRows.isNotEmpty()) t("report.countsLine.registro_s_sin_hora_unica", int(a.episodes.unplacedCollectorRows.size)) else "") + "."
    }

    /** Estados degradados de Radio por tipo; no se convierten todos en «pérdidas de cobertura». */
    fun serviceByType(signals: List<Signal>): String {
        val parts = signals.flatMap { it.text.split(" · ") }.groupingBy { it }.eachCount()
        val label = mapOf("OUT_OF_SERVICE" to t("report.serviceByType.sin_servicio"), "EMERGENCY_ONLY" to t("report.serviceByType.solo_emergencias"), "searching" to t("report.serviceByType.buscando_red"), "OPERATOR_NOT_SIM" to t("report.serviceByType.operador_distinto_de_la_sim"))
        return parts.entries.sortedByDescending { it.value }.joinToString(", ") { "${int(it.value)} ${label[it.key] ?: it.key.lowercase()}" }
    }

    private fun whyAttention(g: ObservationGroup): String = g.reasons.joinToString(" ") { r ->
        when (r) {
            RelevanceReason.COLLECTOR_ALARM -> t("report.whyAttention.icdetector_lo_marco_como_alarma")
            RelevanceReason.FORENSIC_CASE -> t("report.whyAttention.icdetector_guardo_un_registro_detallado")
            RelevanceReason.IDENTITY_PATTERN -> t("report.whyAttention.afecta_a_como_se_identifica")
            RelevanceReason.TECHNOLOGY_DOWNGRADE -> t("report.whyAttention.las_redes_antiguas_tienen_menos")
            RelevanceReason.PHONE_STATE_COINCIDENCE -> t("report.whyAttention.coincidio_con_un_estado_de")
            else -> ""
        }
    }.trim()

    /** Un solo gráfico: cuándo ocurrió cada tipo de observación. */
    private fun whenChart(ctx: TextContext, a: CaseAnalysis): Block? {
        val groups = a.assessment.groups
        val eps = a.episodes.episodes
        if (eps.isEmpty()) return null
        val ha = a.historyAnalysis
        val from = listOfNotNull(ha?.period?.first?.time?.instantOrNull, eps.minOf { it.start }).min()
        val to = listOfNotNull(ha?.period?.last?.time?.instantOrNull, eps.maxOf { it.end }).max().let { if (!it.isAfter(from)) from.plusSeconds(3600) else it }
        val marks = groups.flatMapIndexed { lane, g ->
            epsOf(a, g).flatMap { e -> e.collectorSignals.map { s -> Block.Timeline.Mark(lane, s.start.toEpochMilli(), s.start.toEpochMilli(), g.relevance != SpoofingRelevance.NO_INDICATION, null, if (g.relevance == SpoofingRelevance.NO_INDICATION) 5 else 1) } }
        }
        return Block.Timeline(t("report.whenChart.cuando_ocurrio_cada_observacion"), groups.map { layShort(it) }, from.toEpochMilli(), to.toEpochMilli(),
            tickFmt(ctx, from, to), marks, emptyList(), zoneNote(ctx),
            t("report.whenChart.cada_marca_es_un_momento") +
                t("report.whenChart.sirve_para_ver_si_se"))
    }

    // ───────────────────────── gráficos por tipo ─────────────────────────

    private fun allRows(a: CaseAnalysis) = a.history?.import?.observations.orEmpty().filter { it.time.instantOrNull != null }.sortedBy { it.time.instantOrNull }

    private fun chartFor(ctx: TextContext, a: CaseAnalysis, eps: List<Episode>, compact: Boolean = false): List<Block>? = when (eps.first().mainPattern) {
        PatternKind.BAND_CHANGE -> bandChart(ctx, a, eps, compact)
        PatternKind.RF_IDENTITY -> pciChart(ctx, a, eps.first())?.let { listOf(it) }
        PatternKind.POWER_DEVIATION -> powerChart(ctx, a, eps.first())?.let { listOf(it) }
        PatternKind.SERVICE_DEGRADED -> serviceChart(ctx, a.serviceEvents, eps.first().start.minus(Duration.ofHours(1)), eps.first().end.plus(Duration.ofHours(1)), t("report.chartFor.alrededor_de_e", eps.first().number))?.let { listOf(it) }
        else -> null
    }

    private fun tickFmt(ctx: TextContext, from: Instant, to: Instant) = ChartSupport.ticks(from, to, ctx.displayZone) { z, step ->
        (if (step >= Duration.ofDays(1)) fmt.DAY else if (Duration.between(from, to) > Duration.ofHours(20)) fmt.SHORT else fmt.HM).format(z)
    }

    private fun epLabel(eps: List<Episode>) = eps.joinToString(", ") { "E${it.number}" }

    /** Grupos con más de un episodio, numerados G1, G2… en orden cronológico. */
    private fun groupLabels(a: CaseAnalysis): Map<Int, String> =
        a.episodes.groups.filter { it.episodes.size > 1 }.mapIndexed { i, g -> g.number to "G${i + 1}" }.toMap()

    private val GREY = 5

    /** Color de una celda en un gráfico: las celdas de los episodios mostrados primero, sin usar el gris. */
    private fun colorOf(ctx: TextContext, key: String, eps: List<Episode>): Int {
        val primary = eps.flatMap { e -> e.identities.map { it.key } }.distinct()
        val i = primary.indexOf(key).takeIf { it >= 0 } ?: (primary.size + ctx.cells.index(key))
        val palette = listOf(0, 1, 3, 4, 2, 6, 7)
        return palette[i % palette.size]
    }

    /** Cambio de banda: banda de la servidora fila a fila y potencia antes y después. */
    private fun bandChart(ctx: TextContext, a: CaseAnalysis, eps: List<Episode>, compact: Boolean = false): List<Block>? {
        val from = eps.minOf { it.start }.minus(Duration.ofMinutes(20)); val to = eps.maxOf { it.end }.plus(Duration.ofMinutes(20))
        val rows = allRows(a).filter { val t = it.time.instantOrNull!!; !t.isBefore(from) && !t.isAfter(to) }
        if (rows.size < 2) return null
        val signalRows = eps.flatMap { e -> e.signals.mapNotNull { it.row } }.map { it.provenance }.toSet()
        val bands = rows.mapNotNull { LteBands.bandOf(it) }.distinct().sortedByDescending { LteBands.nominalMhz(it) ?: 0 }
        val other = rows.any { it.identity.radio != RadioTech.LTE }
        val lanes = bands.map { b -> "B${b}" + (LteBands.nominalMhz(b)?.let { t("report.bandChart.mhz", it) } ?: "") } + (if (other) listOf(t("report.bandChart.otra_tecnologia")) else emptyList())
        if (lanes.isEmpty()) return null
        // Solo se etiquetan y colorean las filas con señal y la fila anterior a cada una (la que muestra el cambio).
        val prevOfSignal = rows.withIndex().filter { (i, o) -> i + 1 < rows.size && rows[i + 1].provenance in signalRows && o.provenance !in signalRows }.map { it.value.provenance }.toSet()
        val focus = rows.filter { it.provenance in signalRows || it.provenance in prevOfSignal }.mapNotNull { it.identity.joinKey }.toSet()
        val marks = rows.mapNotNull { o ->
            val b = LteBands.bandOf(o)
            val lane = if (b != null) bands.indexOf(b) else if (o.identity.radio != RadioTech.LTE) bands.size else return@mapNotNull null
            val k = o.identity.joinKey
            val keyRow = o.provenance in signalRows || o.provenance in prevOfSignal
            val label = if (keyRow) (k?.let { ctx.cells.of(it) } ?: o.identity.radio.name) else null
            val t = o.time.instantOrNull!!.toEpochMilli()
            Block.Timeline.Mark(lane, t, t, o.provenance in signalRows, label, if (keyRow && k != null) colorOf(ctx, k, eps) else GREY)
        }
        val ticks = tickFmt(ctx, from, to)
        val timeline = Block.Timeline(
            title = t("report.bandChart.banda_de_la_celda_servidora", epLabel(eps)), lanes = lanes, from = from.toEpochMilli(), to = to.toEpochMilli(), ticks = ticks,
            marks = marks, bands = eps.map { Band(it.start.toEpochMilli(), it.end.toEpochMilli(), "E${it.number}") }, axisLabel = zoneNote(ctx),
            caption = t("report.bandChart.cada_marca_es_una_fila") +
                t("report.bandChart.etiquetas_y_colores_filas_con")
        )
        val keys = eps.flatMap { e -> e.identities.map { it.key } }.toSet()
        val series = rows.filter { it.dbm != null && it.identity.radio == RadioTech.LTE }.groupBy { o -> o.identity.joinKey?.takeIf { it in keys && ctx.cells.known(it) != null } }
            .map { (k, l) -> Block.PointChart.Series(k?.let { ctx.cells.of(it) } ?: t("report.bandChart.otras_celdas"), l.map { it.time.instantOrNull!!.toEpochMilli() to it.dbm!!.toDouble() }, k?.let { colorOf(ctx, it, eps) } ?: GREY) }
            .sortedBy { it.name }
        val rings = rows.filter { it.provenance in signalRows && it.dbm != null }.map { it.time.instantOrNull!!.toEpochMilli() to it.dbm!!.toDouble() }
        val power = Block.PointChart(
            title = t("report.bandChart.potencia_de_la_servidora_antes", epLabel(eps)), series = series + listOfNotNull(rings.takeIf { it.isNotEmpty() }?.let { Block.PointChart.Series(t("report.bandChart.fila_con_senal_de_icdetector"), it, ring = true) }),
            from = from.toEpochMilli(), to = to.toEpochMilli(), ticks = ticks, yLabel = t("report.bandChart.dbm_rsrp_magnitud_de_getdbm"),
            bands = eps.map { Band(it.start.toEpochMilli(), it.end.toEpochMilli(), "E${it.number}") },
            caption = t("report.bandChart.puntos_medidos_sin_unir_ni"),
            height = if (compact) 95f else 130f
        )
        return listOf(timeline, power)
    }

    /** Identidad RF: PCI y EARFCN de la misma identidad completa (MCC, MNC, área, Cell ID, tecnología) frente al tiempo. */
    /** Carriles como máximo en la figura de PCI (el último agrupa el resto). Con 20 pt por carril cabe en una página. */
    private val MAX_PCI_LANES = 24

    private fun pciChart(ctx: TextContext, a: CaseAnalysis, e: Episode): Block? {
        val from = e.start.minus(EpisodeEngine.CONTEXT); val to = e.end.plus(EpisodeEngine.CONTEXT)
        val keys = e.signals.mapNotNull { it.identity?.joinKey }.toSet()
        val rows = allRows(a).filter { val t = it.time.instantOrNull!!; !t.isBefore(from) && !t.isAfter(to) && it.identity.joinKey in keys && it.pci != null }
        if (rows.isEmpty()) return null
        val allKeys = rows.map { it.pci!! to it.arfcn }.distinct().sortedWith(compareBy({ it.second ?: 0 }, { it.first }))
        // Un carril por par (PCI, EARFCN), con un máximo: con más pares, los de menos filas van a un carril «otros»
        // (la figura no puede pasar de una página). Se mantiene el orden por portadora y PCI.
        val lanesKeys = if (allKeys.size <= MAX_PCI_LANES) allKeys else {
            val byRows = rows.groupingBy { it.pci!! to it.arfcn }.eachCount()
            val kept = allKeys.sortedWith(compareByDescending<Pair<Int, Int?>> { byRows[it] ?: 0 }.thenBy { it.second ?: 0 }.thenBy { it.first })
                .take(MAX_PCI_LANES - 1).toSet()
            allKeys.filter { it in kept }
        }
        val others = allKeys.size - lanesKeys.size
        val laneOf = lanesKeys.withIndex().associate { (i, k) -> k to i }
        val sig = e.signals.mapNotNull { it.row?.provenance }.toSet()
        val marks = rows.map { o ->
            val t = o.time.instantOrNull!!.toEpochMilli()
            Block.Timeline.Mark(laneOf[o.pci!! to o.arfcn] ?: lanesKeys.size, t, t, o.provenance in sig, null, o.identity.joinKey?.let { ctx.cells.index(it) })
        }
        val labels = lanesKeys.map { (p, f) -> t("report.pciChart.pci_earfcn", p, f ?: "?") } + if (others > 0) listOf(t("report.pciChart.others")) else emptyList()
        val caption = t("report.pciChart.filas_del_historial_con_la") + if (others > 0) " " + t("report.pciChart.others_note", others, MAX_PCI_LANES - 1) else ""
        return Block.Timeline(t("report.pciChart.pci_y_earfcn_de_e", cellsOf(ctx, e), e.number), labels,
            from.toEpochMilli(), to.toEpochMilli(), tickFmt(ctx, from, to), marks, listOf(Band(e.start.toEpochMilli(), e.end.toEpochMilli(), "E${e.number}")), zoneNote(ctx),
            caption)
    }

    /** Desviación de potencia: medidas de la celda y referencia histórica de la misma celda. */
    @Suppress("UNCHECKED_CAST")
    private fun powerChart(ctx: TextContext, a: CaseAnalysis, e: Episode): Block? {
        val k = e.identities.firstOrNull()?.key ?: return null
        val from = e.start.minus(Duration.ofHours(1)); val to = e.end.plus(Duration.ofHours(1))
        val rows = allRows(a).filter { val t = it.time.instantOrNull!!; !t.isBefore(from) && !t.isAfter(to) && it.identity.joinKey == k && it.dbm != null }
        if (rows.isEmpty()) return null
        val sig = e.signals.mapNotNull { it.row?.provenance }.toSet()
        val median = (e.measurement.values["measures"] as? List<Map<String, Int>>)?.firstOrNull()?.let { it["median"] to it["n"] }
        return Block.PointChart(t("report.powerChart.potencia_de_y_referencia_historica", ctx.cells.of(k), e.number),
            listOf(Block.PointChart.Series(ctx.cells.of(k), rows.map { it.time.instantOrNull!!.toEpochMilli() to it.dbm!!.toDouble() }, ctx.cells.index(k)),
                Block.PointChart.Series(t("report.bandChart.fila_con_senal_de_icdetector"), rows.filter { it.provenance in sig }.map { it.time.instantOrNull!!.toEpochMilli() to it.dbm!!.toDouble() }, ring = true)),
            from.toEpochMilli(), to.toEpochMilli(), tickFmt(ctx, from, to), t("report.powerChart.dbm_getdbm"), listOf(Band(e.start.toEpochMilli(), e.end.toEpochMilli(), "E${e.number}")),
            t("report.powerChart.filas_de_en_1_h", ctx.cells.of(k)) + (median?.let { t("report.powerChart.linea_discontinua_mediana_de_la", int(it.second!!)) } ?: t("report.powerChart.sin_referencia_historica_suficiente")) +
                t("report.powerChart.sin_interpolacion"),
            refLines = listOfNotNull(median?.let { Block.PointChart.RefLine(it.first!!.toDouble(), t("report.powerChart.mediana_historica_dbm", it.first)) }))
    }

    /** Servicio degradado: cronología de estados de Radio (solo los cambios registrados). */
    private fun serviceChart(ctx: TextContext, events: List<com.alexisgordr.cellcase.core.model.ServiceStateEvent>, from: Instant, to: Instant, scope: String): Block? {
        val ev = events.filter { it.time != null && !it.time.isBefore(from) && !it.time.isAfter(to) }
        if (ev.isEmpty()) return null
        val states = ev.mapNotNull { it.state }.distinct().sortedBy { if (it == "IN_SERVICE") 0 else 1 }
        val marks = ev.filter { it.state != null }.map { val t = it.time!!.toEpochMilli(); Block.Timeline.Mark(states.indexOf(it.state), t, t, it.state != "IN_SERVICE") }
        return Block.Timeline(t("report.serviceChart.cambios_de_estado_de_servicio", scope), states, from.toEpochMilli(), to.toEpochMilli(), tickFmt(ctx, from, to), marks, emptyList(), zoneNote(ctx),
            t("report.serviceChart.cada_marca_es_un_cambio"))
    }

    // ───────────────────────── cronología (completo) ─────────────────────────

    override fun chronology(ctx: TextContext, a: CaseAnalysis): List<Block> {
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("report.chronology.cronologia"))
        val eps = a.episodes.episodes
        val allSignals = eps.flatMap { it.signals } + a.episodes.isolatedServiceSignals
        val ha = a.historyAnalysis
        val starts = listOfNotNull(ha?.period?.first?.time?.instantOrNull, allSignals.minOfOrNull { it.start }, a.serviceEvents.mapNotNull { it.time }.minOrNull())
        val ends = listOfNotNull(ha?.period?.last?.time?.instantOrNull, allSignals.maxOfOrNull { it.end }, a.serviceEvents.mapNotNull { it.time }.maxOrNull())
        if (starts.isEmpty() || ends.isEmpty()) { out += Block.Paragraph(t("report.chronology.no_hay_datos_con_hora")); return out }
        val from = starts.min(); val to = ends.max().let { if (!it.isAfter(from)) from.plusSeconds(3600) else it }
        val lanes = listOf(SignalSource.HISTORY_COLLECTOR, SignalSource.FORENSIC_CASE, SignalSource.STABLE_SITE_SHADOW, SignalSource.SERVICE_STATE)
        val marks = allSignals.map { s -> Block.Timeline.Mark(lanes.indexOf(s.source), s.start.toEpochMilli(), s.start.toEpochMilli(), !s.subthreshold) }
        val histMarks = ha?.import?.observations?.mapNotNull { it.time.instantOrNull }?.map { Block.Timeline.Mark(lanes.size, it.toEpochMilli(), it.toEpochMilli(), false) }.orEmpty()
        out += Block.Timeline(t("report.chronology.senales_por_fuente_en_el"), listOf(t("report.chronology.historial_icdetector"), t("report.chronology.caso_forense"), t("report.sourceLabel.stable_site"), t("report.chronology.radio_degradado"), t("report.chronology.filas_del_historial")),
            from.toEpochMilli(), to.toEpochMilli(), tickFmt(ctx, from, to), marks + histMarks,
            a.episodes.groups.map { Band(it.start.toEpochMilli(), it.end.toEpochMilli(), groupLabels(a)[it.number] ?: "E${it.episodes.first()}") }, zoneNote(ctx),
            t("report.chronology.cada_marca_es_un_registro"))
        if (ha != null && ha.period.rowsPerDay.isNotEmpty()) out += Block.BarChart(t("report.chronology.filas_del_historial_por_fecha"), ha.period.rowsPerDay.map { fmt.DAY.format(it.first) },
            ha.period.rowsPerDay.map { it.second }, t("report.chronology.filas_por_dia"), t("report.chronology.fecha_local_del_telefono_la"))
        if (a.serviceEvents.isNotEmpty()) {
            val t = a.serviceEvents.mapNotNull { it.time }
            serviceChart(ctx, a.serviceEvents, t.min(), t.max().let { if (it == t.min()) it.plusSeconds(3600) else it }, t("report.chronology.todo_el_periodo"))?.let { out += it }
        }
        if (ha != null && ha.sampling.evaluable && ha.sampling.gaps.isNotEmpty()) out += Block.Table(
            listOf(Column(t("report.chronology.desde_ultima_fila_antes"), 3f), Column(t("report.chronology.hasta_primera_fila_despues"), 3f), Column(t("report.chronology.duracion"), 1.2f, Align.RIGHT, noWrap = true)),
            ha.sampling.gaps.sortedByDescending { it.duration }.take(10).map { g -> listOf("${local(g.before.time.local!!)} · ${ref(g.before.provenance)}", "${local(g.after.time.local!!)} · ${ref(g.after.provenance)}", dur(g.duration)) },
            t("report.chronology.intervalos_sin_registros_de_60"))
        return out
    }

    // ───────────────────────── conclusiones y limitaciones (comunes) ─────────────────────────

    override fun conclusions(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block> {
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("report.conclusions.conclusion_y_proximos_pasos"))
        val eps = a.episodes.episodes
        val review = reviewEpisodes(a)
        val c = ArrayList<String>()
        c += when {
            eps.isEmpty() -> t("report.conclusions.no_hay_senales_de_icdetector")
            review.isEmpty() -> t("report.conclusions.ninguna_observacion_merece_revision_con")
            review.none { it.priority == Priority.PRIORITY } -> t("report.conclusions.no_hay_incidencias_de_revision", review.joinToString { "E${it.number}" })
            else -> t("report.conclusions.revision_prioritaria_revisar", review.filter { it.priority == Priority.PRIORITY }.joinToString { "E${it.number}" }, review.filter { it.priority == Priority.REVIEW }.joinToString { "E${it.number}" }.ifEmpty { t("report.conclusions.ninguna") })
        }
        if (eps.isNotEmpty() && eps.none { it.hasAlarm }) c += t("report.conclusions.icdetector_no_registro_alarmas_las")
        c += t("report.conclusions.valoracion_para_la_pregunta_de", overallStatement(a))
        c += t("report.conclusions.no_se_puede_establecer_una")
        out += Block.Labeled(t("report.conclusions.conclusion"), c)
        val n = LinkedHashSet<String>()
        review.mapNotNull { e -> nextChecks(e).firstOrNull()?.let { it to e.number } }.groupBy({ it.first }, { it.second })
            .forEach { (action, nums) -> n += "${nums.joinToString(", ") { "E${it}" }}: ${action.lowerFirst()}" }
        if (a.forensicCases.isEmpty() && review.isNotEmpty()) n += t("report.conclusions.si_icdetector_abrio_casos_forenses")
        availability.filter { it.status == Availability.IMPORT_FAILED || it.status == Availability.NOT_COMPATIBLE }.forEach { n += t("report.conclusions.volver_a_exportar_los_archivos", it.files.joinToString()) }
        if (!ctx.case.zone.confirmed) n += t("report.conclusions.confirmar_la_zona_horaria_del")
        if (n.isEmpty()) n += t("report.conclusions.seguir_capturando_con_normalidad_repetir")
        out += Block.Labeled(t("report.conclusions.proximos_pasos"), n.toList())
        return out
    }

    override fun limitations(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block> {
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("report.limitations.limitaciones"))
        val l = ArrayList<String>()
        l += t("report.limitations.las_heuristicas_de_icdetector_no")
        l += t("report.limitations.historial_caso_forense_y_stable")
        l += t("report.limitations.una_sola_observacion_tiene_duracion")
        val col = a.historyAnalysis?.collector
        if (col == null || col.verified.values.sum() > 0) l += t("report.limitations.los_estados_de_verificacion_verified")
        if (col != null && col.verificationRetired > 0) l += if (col.verificationMixed)
            t("report.limitations.verification_retired_mixed", int(col.verificationRetired.toLong()), int(col.verified.values.sum().toLong()))
            else t("report.limitations.verification_retired_all", int(col.verificationRetired.toLong()))
        if (a.topology != null || a.geometry != null || a.stableSite != null) l += t("report.limitations.topologia_geometria_y_stable_site")
        if (a.localProfile?.evaluated?.let { it > 0 } == true) l += t("profile.limitation")
        val missing = availability.filter { it.status != Availability.AVAILABLE }
        if (missing.isNotEmpty()) l += t("report.limitations.analisis_no_disponibles") + missing.joinToString("; ") { "${analysisLabel(it.code).lowercase()} (${availabilityLabel(it.status).lowercase()})" } + "."
        l += t("report.limitations.el_dispositivo_la_zona_horaria") +
            (if (ctx.case.discrepancies.isEmpty()) t("report.limitations.no_se_han_detectado_discrepancias") else t("report.limitations.avisos") + ctx.case.discrepancies.joinToString(" ") { discrepancyText(it) })
        out += Block.Bullets(l, TextStyle.SMALL)
        return out
    }

    override fun methodology(ctx: TextContext, a: CaseAnalysis): List<Block> = listOf(
        Block.Heading(1, t("report.methodology.metodologia")),
        Block.Paragraph(t("report.methodology.cada_fila_del_historial_con") +
            t("report.methodology.las_senales_de_la_misma", EpisodeEngine.SAME_IDENTITY_GAP.toMinutes(), EpisodeEngine.GROUP_GAP.toMinutes()) +
            t("report.methodology.radio_se_asocia_a_un", EpisodeEngine.SERVICE_LINK.toMinutes())),
        Block.Bullets(listOf(
            t("report.methodology.observacion_lo_registrado_por_icdetector"),
            t("report.methodology.comprobacion_de_cellcase_contraste_con", EpisodeEngine.BASELINE_MIN_ROWS, EpisodeEngine.POWER_DIFF_DB),
            t("report.methodology.persistencia_dentro_del_episodio_momentos", EpisodeEngine.SUSTAINED_MOMENTS, EpisodeEngine.SUSTAINED_SPAN.toMinutes()),
            t("report.methodology.revision_prioritaria_alarma_de_icdetector"),
            t("report.methodology.revisar_alarma_de_icdetector_o"),
            t("report.methodology.contexto_el_resto_los_estados"),
            t("report.methodology.versiones_cambios_en_docs_episode", READERS_VERSION, RULESET_VERSION, EPISODE_RULES_VERSION, CROSS_RULES_VERSION) +
                (if (ctx.results.any { it.kind == ExportKind.BACKUP }) " " + t("report.methodology.backup_version", com.alexisgordr.cellcase.core.backup.IcdBackupFormat.READER_VERSION) else "") +
                (if (a.localProfile != null) " " + t("report.methodology.profile_version", PROFILE_RULES_VERSION) else "") +
                (if (a.churn != null) " " + t("report.methodology.churn_version", com.alexisgordr.cellcase.core.analysis.CHURN_RULES_VERSION) else "")
        ), TextStyle.SMALL)
    )

    override fun analyst(ctx: TextContext): List<Block> {
        val n = ctx.case.analystNotes?.trim()
        return listOf(Block.Heading(1, t("report.analyst.anotaciones_del_analista")),
            if (n.isNullOrEmpty()) Block.Paragraph(t("report.analyst.el_analista_no_ha_anadido"))
            else Block.Callout(CalloutKind.ANALYST, t("report.analyst.texto_escrito_por_el_analista"), n))
    }


    // ───────────────────────── 4. contexto de red ─────────────────────────

    override fun networkContext(ctx: TextContext, a: CaseAnalysis): List<Block> {
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("report.networkContext.contexto_de_red"))
        out += Block.Paragraph(t("report.networkContext.topologia_geometria_y_stable_site"))
        // Topología
        a.topology?.let { sel ->
            val t = sel.primary
            out += Block.Heading(2, t("report.networkContext.transiciones_entre_celdas_topologia"))
            val failed = t.routes.count { it.lastStatus == "FAILED" }
            out += Block.KeyValues(listOfNotNull(
                t("report.label.contenido") to t("report.networkContext.rutas_dirigidas_handovers_acumulados_generado", int(t.routes.size), int(t.routes.sumOf { it.observations ?: 0 }), t.generatedAt?.let { at(ctx, it) } ?: "?"),
                t("report.networkContext.limite") to if (t.possibleTruncation) t("report.networkContext.se_alcanza_el_maximo_de") else t("report.networkContext.por_debajo_del_limite_de"),
                t("report.networkContext.ultimo_estado") to t("report.networkContext.ruta_s_con_el_ultimo", int(failed)),
                if (sel.others.isNotEmpty()) t("report.networkContext.otras_exportaciones") to t("report.networkContext.exportacion_es_anterior_es_de", int(sel.others.size)) else null
            ))
            topologyGraph(ctx, a)?.let { out += it }
        }
        // Radio
        if (a.serviceEvents.isNotEmpty()) {
            out += Block.Heading(2, t("report.networkContext.estado_de_servicio_radio"))
            val ev = a.serviceEvents
            val byState = ev.groupingBy { it.state ?: "?" }.eachCount().entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key} ${int(it.value)}" }
            val items = mutableListOf(
                t("report.label.contenido") to t("report.networkContext.cambio_s_de_estado_entre", int(ev.size), ev.firstOrNull()?.time?.let { at(ctx, it) } ?: "?", ev.lastOrNull()?.time?.let { at(ctx, it) } ?: "?"),
                t("report.label.estados") to byState,
                t("report.networkContext.operador_distinto_de_la_sim") to int(ev.count { it.operatorDiffersFromSim }),
                t("report.networkContext.estados_degradados_sin_otras_senales") to (serviceByType(a.episodes.isolatedServiceSignals).ifEmpty { t("report.header.ninguno") } + t("report.networkContext.no_forman_episodio"))
            )
            if (a.cross?.findings?.any { it.check == CrossCheck.SERVICE_STATE } == true)
                items += t("report.networkContext.coherencia_con_el_historial") to t("cross.see_section")
            if (a.serviceDuplicates > 0) items += t("report.networkContext.eventos_repetidos") to t("report.networkContext.evento_s_identicos_en_varias", int(a.serviceDuplicates))
            out += Block.KeyValues(items)
        }
        // Casos forenses
        if (a.forensicCases.isNotEmpty()) {
            out += Block.Heading(2, t("report.networkContext.casos_forenses"))
            out += Block.Table(
                listOf(Column(t("report.networkContext.caso"), 1.9f, noWrap = true), Column(t("report.networkContext.estado_origen"), 1.7f), Column(t("report.networkContext.ventana"), 2.2f), Column(t("report.networkContext.muestras"), 0.9f, Align.RIGHT), Column(t("report.networkContext.celda"), 2.6f, mono = true, noWrap = true), Column(t("report.networkContext.confirmado"), 1f)),
                a.forensicCases.map { (_, c) ->
                    listOf(c.meta.caseCode ?: "?", "${c.meta.state ?: "?"} / ${c.meta.captureOrigin ?: "?"}" +
                        if (c.meta.captureOrigin == "ALARM") t("report.networkContext.alarma") else t("report.networkContext.observacion_no_alarma"),
                        "${c.start?.let { short(ctx, it) } ?: "?"} – ${c.end?.let { SHORT.format(it.atZone(ctx.displayZone)).takeLast(5) } ?: "?"}",
                        int(c.samples.size), idText(ctx, c.meta.cellIdentity), when (c.meta.confirmed) { true -> t("report.networkContext.si"); false -> t("report.networkContext.no"); null -> "?" })
                }, t("report.networkContext.casos_forenses_importados_valoraciones_de"))
            val multi = a.forensicVersions.filter { it.all.size > 1 }
            if (multi.isNotEmpty()) out += Block.Table(
                listOf(Column(t("report.networkContext.caso"), 1.8f, noWrap = true), Column(t("report.networkContext.versiones"), 0.9f, Align.RIGHT), Column(t("report.networkContext.relacion"), 1.4f), Column(t("report.networkContext.usadas_en_el_analisis"), 2.4f), Column(t("report.networkContext.explicacion"), 3.5f)),
                multi.map { g -> listOf(g.caseCode ?: "?", int(g.all.size), versionLabel(g.relation),
                    g.used.joinToString(", ") { id -> fileOf(ctx, id) }, versionExplanation(g)) },
                t("report.networkContext.versiones_exportadas_de_un_mismo", MERGE_RULES_VERSION))
            // conflictos entre versiones e internos: se presentan, no se resuelven (D-043)
            val conflictRows = a.forensicVersions.flatMap { g ->
                g.conflicts.map { c -> listOf(g.caseCode ?: "?", conflictField(c.field), c.key ?: "—", "${c.a} (${fileOf(ctx, c.aImport)})", "${c.b} (${fileOf(ctx, c.bImport)})") } +
                    g.internal.flatMap { (id, l) -> l.map { c -> listOf(g.caseCode ?: "?", conflictField(c.field) + " · " + t("report.versions.interno"), c.key ?: "—", c.a, c.b + " (" + fileOf(ctx, id) + ")") } }
            }
            if (conflictRows.isNotEmpty()) out += Block.Table(
                listOf(Column(t("report.networkContext.caso"), 1.6f, noWrap = true), Column(t("report.versions.campo"), 1.6f), Column(t("report.versions.clave"), 1.8f),
                    Column(t("report.versions.valor_a"), 2.2f), Column(t("report.versions.valor_b"), 2.2f)),
                conflictRows, t("report.versions.conflictos_entre_versiones_y_dentro", ForensicVersions.MAX_CONFLICTS))
        }
        // Stable-Site
        a.stableSite?.let { sel ->
            val s = sel.primary
            out += Block.Heading(2, t("report.networkContext.lugares_habituales_stable_site"))
            out += Block.Paragraph(t("report.networkContext.cada_lugar_se_registra_en"), TextStyle.SMALL)
            out += Block.Table(
                listOf(Column(t("report.networkContext.clave"), 1.6f, mono = true, noWrap = true), Column(t("report.networkContext.estado"), 1.4f), Column(t("report.networkContext.dias_serv"), 0.9f, Align.RIGHT), Column(t("report.networkContext.dias_estaticos"), 1f, Align.RIGHT),
                    Column(t("report.networkContext.obs_servidora"), 1.1f, Align.RIGHT), Column(t("report.networkContext.celdas"), 0.8f, Align.RIGHT)),
                s.sites.sortedByDescending { it.servingObservations ?: 0 }.take(10).map { site ->
                    listOf(site.key.take(10) + "…", site.featureState ?: "?", site.servingDays?.let { int(it) } ?: "—", site.staticDays?.let { int(it) } ?: "—",
                        site.servingObservations?.let { int(it) } ?: "—", int(s.siteCells.count { it.key == site.key }))
                }, t("report.networkContext.claves_de_sitio_con_mas", int(s.sites.size)))
            val rec = s.episodes.count { it.shadowRecorded == true }
            out += Block.Paragraph(t("report.networkContext.episodios_en_sombra_de_los", int(s.episodes.size), int(rec)), TextStyle.SMALL)
        }
        // Geometría
        a.geometry?.let { sel ->
            val g = sel.primary
            out += Block.Heading(2, t("report.networkContext.movilidad_geometria"))
            val fam = g.cells.groupingBy { it.familiarity ?: "?" }.eachCount().entries.joinToString("; ") { "${it.key} ${int(it.value)}" }
            val invalid = g.trips.count { it.state == "CLOSED" && it.meetsValidityRule == false }
            out += Block.KeyValues(listOf(
                t("report.label.contenido") to t("report.networkContext.celdas_rutas_y_viajes_sin", int(g.cells.size), int(g.edges.size), int(g.trips.size)),
                t("report.label.familiaridad") to t("report.networkContext.known_on_route_significa_ruta", fam),
                t("report.label.viajes") to t("report.networkContext.abierto_s_cerrado_s_que", int(g.trips.count { it.state == "OPEN" }), int(invalid)),
                t("report.networkContext.limite") to if (g.possibleTruncation) t("report.networkContext.posible_truncamiento_1_000_rutas") else t("report.networkContext.por_debajo_del_limite_de_2")
            ))
        }
        // Agregados con exportaciones sin orden demostrable: no se usa ninguna (cross/2); el detalle está en X-VER.
        a.aggregateConflicts.forEach { cf ->
            out += Block.Paragraph(t("report.networkContext.agregado_en_conflicto", kindLabel(cf.kind), int(cf.versions.size.toLong()), cf.versions.joinToString(", ") { it.fileName }), TextStyle.EMPHASIS)
        }
        if (a.topology == null && a.serviceEvents.isEmpty() && a.stableSite == null && a.geometry == null && a.forensicCases.isEmpty() && a.aggregateConflicts.isEmpty())
            out += Block.Paragraph(t("report.networkContext.no_se_ha_aportado_ninguna"))
        return out
    }

    private fun topologyGraph(ctx: TextContext, a: CaseAnalysis): Block? {
        val t = a.topology?.primary ?: return null
        val routes = t.routes.filter { it.fromRaw.isNotEmpty() && it.toRaw.isNotEmpty() && it.fromRaw != it.toRaw }
        if (routes.isEmpty()) return null
        val weight = HashMap<String, Int>()
        routes.forEach { r -> weight.merge(r.fromRaw, r.observations ?: 0, Int::plus); weight.merge(r.toRaw, r.observations ?: 0, Int::plus) }
        val episodeKeys = a.episodes.episodes.flatMap { e -> e.identities.map { it.key } }.toSet()
        val maxNodes = 14
        val chosen = LinkedHashSet<String>()
        weight.keys.filter { it in episodeKeys }.sortedByDescending { weight[it] }.take(6).forEach { chosen += it }
        weight.entries.sortedByDescending { it.value }.forEach { if (chosen.size < maxNodes) chosen += it.key }
        val nodes = chosen.toList()
        val idx = nodes.withIndex().associate { it.value to it.index }
        val edges = routes.filter { it.fromRaw in idx && it.toRaw in idx }
        val omittedNodes = weight.size - nodes.size
        val omittedEdges = routes.size - edges.size
        return Block.Graph(
            title = t("report.topologyGraph.transiciones_observadas_entre_celdas_agregado"),
            nodes = nodes.map { Block.Graph.Node("n${idx[it]!! + 1}", it in episodeKeys) },
            edges = edges.map { Block.Graph.Edge(idx[it.fromRaw]!!, idx[it.toRaw]!!, it.observations ?: 0, it.lastStatus == "FAILED") },
            legend = nodes.mapIndexed { i, k -> t("report.topologyGraph.n_handovers", i + 1, key(ctx, k), int(weight[k] ?: 0)) + if (k in episodeKeys) t("report.topologyGraph.en_episodio") else "" },
            caption = t("report.topologyGraph.solo_se_dibujan_rutas_presentes") +
                t("report.topologyGraph.nodos_resaltados_celdas_que_aparecen", maxNodes) +
                t("report.topologyGraph.omitidas_celda_s_y_ruta", int(omittedNodes), int(omittedEdges))
        )
    }

    // ───────────────────────── coincidencias y diferencias entre fuentes (cross/1) ─────────────────────────
    // Solo presentación: todo sale de CaseAnalysis.cross (tipo, motivo, valores, derivación). Lo usan el informe
    // técnico, el explicado y la app, en los dos idiomas.

    fun crossSourceLabel(k: ExportKind) = t("cross.src." + k.name)
    fun crossCheckLabel(c: CrossCheck) = t("cross.check." + c.name)
    fun crossOutcomeLabel(o: CrossOutcome) = t("cross.outcome." + o.name)
    /** «Historial / Topología, Geometría». */
    fun crossSources(f: CrossFinding) = crossSourceLabel(f.sourceA) + " / " + f.sourcesB.joinToString(", ") { crossSourceLabel(it) }
    /** Margen de la ventana del resultado («60 s», «1 min»); las frases que lo citan lo reciben como {0}. */
    fun crossWindow(f: CrossFinding): String = f.window?.let { w -> if (w.toMinutes() >= 1 && w.seconds % 60 == 0L) t("report.dur.min", w.toMinutes()) else "${w.seconds} s" } ?: "—"
    fun crossExplanation(f: CrossFinding) = t("cross.reason." + f.reason.name, crossWindow(f))
    fun crossLimitations(f: CrossFinding): String = listOfNotNull(
        t("cross.derivation." + f.derivation.name),
        if (f.outcome == CrossOutcome.DIFFERENCE) t("cross.difference_note") else null,
        f.next?.let { t("cross.next_prefix").trimEnd() + " " + t("cross.next." + it.name) }
    ).joinToString(" ")
    fun crossCounts(r: CrossResult) = t("cross.counts", int(r.count(CrossOutcome.MATCH).toLong()), int(r.count(CrossOutcome.DIFFERENCE).toLong()),
        int(r.count(CrossOutcome.COMPLEMENTARY).toLong()), int(r.count(CrossOutcome.NOT_COMPARABLE).toLong()))
    /** Por qué no hay nada comparable (null si sí lo hay). */
    fun crossWhyNone(r: CrossResult): String? = when {
        r.why != null -> t("cross.none." + r.why.name)
        !r.anyComparable -> t("cross.none.only_context")
        else -> null
    }

    /** Entidad legible: identidad (con su identificador corto si lo tiene), ruta «A → B», episodio de Stable-Site o caso. */
    private fun crossEntity(e: String, id: (String) -> String): String = when {
        e == "SERVICE" -> ""
        '>' in e -> e.split('>').joinToString(" → ") { id(it) }
        Regex("^[0-9a-f]{8}-[0-9a-f-]{27}$").matches(e) -> e.take(8) + "…"
        Regex("^\\d{3}-\\d{2,3}-").containsMatchIn(e) -> id(e)
        else -> e
    }

    private fun crossValue(f: CrossField, v: String?, id: (String) -> String, time: (Instant) -> String): String? = when {
        v == null -> null
        f == CrossField.EXPORT_TIME -> runCatching { time(Instant.parse(v)) }.getOrDefault(v)
        f == CrossField.CONTENT -> v.split(';').joinToString(", ") { kv -> kv.split('=').let { (k, n) -> int(n.toLong()) + " " + t("cross.content.$k") } }
        f == CrossField.LAST_SEEN -> runCatching { time(Instant.parse(v)) }.getOrDefault(v)
        f == CrossField.SERVING_CELL || f == CrossField.TRANSITIONS -> v.split(" + ").joinToString(" + ") { crossEntity(it, id) }
        v.all { it.isDigit() } && v.length < 12 -> int(v.toLong())
        else -> v
    }

    /** Fuente del valor b cuando un resultado agrupa varios agregados: el campo indica de cuál procede. */
    private fun crossSourceOfB(f: CrossFinding, field: CrossField): ExportKind = if (f.alsoB.isEmpty()) f.sourceB else when (field) {
        CrossField.HANDOVERS -> ExportKind.TOPOLOGY
        CrossField.FAMILIARITY -> ExportKind.GEOMETRY
        CrossField.MAX_SERVING_DAYS -> ExportKind.STABLE_SITE
        else -> f.sourceB
    }

    /** Valores comparados: «Historial IN_SERVICE · Radio POWER_OFF (111)». Como mucho [max]. */
    fun crossCompared(f: CrossFinding, id: (String) -> String, time: (Instant) -> String, max: Int = 3): List<String> =
        // Un campo sin valor en ninguna fuente (p. ej. la celda del caso que no aparece en el agregado) no se escribe.
        f.compared.take(max).mapNotNull { c ->
            val a = (crossValue(c.field, c.a, id, time) ?: if (c.field == CrossField.EXPORT_TIME) t("cross.no_date") else null)
                ?.let { if (f.check == CrossCheck.AGGREGATE_VERSIONS) it else crossSourceLabel(f.sourceA) + " " + it }
            val b = crossValue(c.field, c.b, id, time)?.let { crossSourceLabel(crossSourceOfB(f, c.field)) + " " + it }
            val ent = c.entity?.let { e -> (runCatching { time(Instant.parse(e)) }.getOrNull() ?: crossEntity(e, id)) + ": " } ?: ""
            if (a == null && b == null) null
            else ent + t("cross.field." + c.field.name) + ": " + listOfNotNull(a, b).joinToString(" · ") + if (c.count > 1) " (${int(c.count.toLong())})" else ""
        } + if (f.compared.size > max) listOf(t("cross.more", int((f.compared.size - max).toLong()))) else emptyList()

    /** Detalle de un resultado: elementos, entidades, valores, periodo y ventana. Sin referencias. */
    fun crossDetail(f: CrossFinding, id: (String) -> String, time: (Instant) -> String, maxEntities: Int = 3, maxCompared: Int = 3): String {
        val parts = ArrayList<String>()
        val unitKey = listOf("cross.unit.reason.${f.reason.name}", "cross.unit.${f.check.name}.${f.sourceB.name}", "cross.unit.${f.check.name}", "cross.unit.items").first { Messages.has(language, it) }
        val unit = t(unitKey, int(f.count.toLong()))
        val ents = f.entities.map { crossEntity(it, id) }.filter { it.isNotEmpty() }
        parts += unit + if (ents.isEmpty()) "" else ": " + ents.take(maxEntities).joinToString(", ") + if (ents.size > maxEntities) " " + t("cross.more", int((ents.size - maxEntities).toLong())) else ""
        parts += crossCompared(f, id, time, maxCompared)
        if (f.start != null) parts += time(f.start) + if (f.end != null && f.end != f.start) " – " + time(f.end) else ""
        if (f.window != null) parts += t("cross.window", crossWindow(f))
        return parts.joinToString(". ") + "."
    }

    /** Referencias «archivo! entrada:línea» (con un espacio tras «!» para que la tabla pueda partir nombres largos). */
    fun crossRefs(f: CrossFinding, max: Int = 3) = if (f.refs.isEmpty()) "" else
        t("cross.refs", f.refs.take(max).joinToString("; ") { it.shortRef().replace("!", "! ") } + if (f.refs.size > max) " …" else "")

    /** Frase sencilla para el informe explicado, si existe para esa comprobación y motivo. */
    fun crossLay(f: CrossFinding): String? = ("explained.cross." + f.check.name + "." + f.reason.name).let { k -> if (Messages.has(language, k)) t(k, crossWindow(f)) else null }

    /** En el técnico, cada identidad citada recibe un identificador corto (C1…); las nuevas se explican bajo la tabla. */
    private fun crossId(ctx: TextContext): (String) -> String = { k -> ctx.cells.of(k) }

    /** Tabla con un resultado por fila: el listado completo (anexo opcional). */
    private fun crossFullTable(ctx: TextContext, r: CrossResult): Block.Table {
        val id = crossId(ctx); val time: (Instant) -> String = { short(ctx, it) }
        return Block.Table(
            listOf(Column(t("cross.col.comparacion"), 1.45f), Column(t("cross.col.resultado"), 1.3f), Column(t("cross.col.detalle"), 3.75f), Column(t("cross.col.limitaciones"), 2.6f)),
            r.findings.map { f ->
                listOf("${f.check.id}\n${crossCheckLabel(f.check)}\n${crossSources(f)}", crossOutcomeLabel(f.outcome),
                    crossExplanation(f) + " " + (crossDetail(f, id, time, maxEntities = 2, maxCompared = 2) + " " + crossRefs(f, 2)).trimEnd(), crossLimitations(f))
            }, t("cross.caption_full", CROSS_RULES_VERSION, int(r.findings.size.toLong())))
    }

    /** Fuentes de un grupo: «Historial / Topología, Geometría». */
    fun crossGroupSources(g: CrossGroup) = crossSourceLabel(g.sourceA) + " / " + g.sourcesB.joinToString(", ") { crossSourceLabel(it) }
    fun crossGroupExplanation(g: CrossGroup) = crossExplanation(g.findings.first())
    fun crossGroupLimitations(g: CrossGroup) = crossLimitations(g.findings.first().copy(next = g.next))

    /** Alcance de un grupo: resultados, elementos, entidades distintas y periodo. */
    fun crossGroupScope(g: CrossGroup, time: (Instant) -> String): String = listOfNotNull(
        t("cross.group.results", int(g.results.toLong())),
        t("cross.group.items", int(g.items.toLong())),
        g.entities.filter { it != "SERVICE" }.size.takeIf { it > 0 }?.let { t("cross.group.entities", int(it.toLong())) },
        g.start?.let { s0 -> time(s0) + (g.end?.takeIf { it != s0 }?.let { " – " + time(it) } ?: "") }
    ).joinToString(" · ")

    /** Ejemplos representativos: los primeros resultados del grupo, abreviados, con sus referencias. */
    fun crossGroupExamples(g: CrossGroup, id: (String) -> String, time: (Instant) -> String, n: Int = 2): String {
        val ex = g.examples(n).joinToString(" ") { f -> "• " + crossDetail(f, id, time, maxEntities = 2, maxCompared = 1) }
        val refs = g.refs(n).take(3)
        return ex + (if (refs.isEmpty()) "" else " " + t("cross.refs", refs.joinToString("; ") { it.shortRef().replace("!", "! ") })) +
            if (g.results > n) " " + t("cross.group.more_results", int((g.results - n).toLong())) else ""
    }

    override fun crossSources(ctx: TextContext, a: CaseAnalysis): List<Block> {
        val r = a.cross ?: return emptyList()
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("cross.title"))
        out += Block.Paragraph(t("cross.intro"))
        crossWhyNone(r)?.let { out += Block.Paragraph(it, TextStyle.EMPHASIS) }
        if (r.findings.isEmpty()) return out
        val v = CrossSummary.of(r)
        out += Block.Paragraph(crossCounts(r) + " " + t("cross.totals_note"))
        val id = crossId(ctx); val time: (Instant) -> String = { short(ctx, it) }
        val before = ctx.cells.entries.size
        // Resumen: todos los resultados, agrupados por comprobación, resultado, motivo y fuentes.
        out += Block.Heading(2, t("cross.summary_title"))
        out += Block.Table(
            listOf(Column(t("cross.col.comparacion"), 1.45f), Column(t("cross.col.resultado_alcance"), 1.75f), Column(t("cross.col.explicacion_ejemplos"), 3.75f), Column(t("cross.col.limitaciones"), 2.3f)),
            v.summary.map { g ->
                listOf("${g.check.id}\n${crossCheckLabel(g.check)}\n${crossGroupSources(g)}", crossOutcomeLabel(g.outcome) + "\n" + crossGroupScope(g, time),
                    crossGroupExplanation(g) + " " + crossGroupExamples(g, id, time), crossGroupLimitations(g))
            }, t("cross.caption_summary", CROSS_RULES_VERSION, int(v.summary.size.toLong()), int(v.totalResults.toLong())))
        // Detalle: diferencias y cruces vinculados a episodios, sin repetir las limitaciones del resumen.
        out += Block.Heading(2, t("cross.detail_title"))
        if (v.detail.isEmpty()) out += Block.Paragraph(t("cross.detail_none"))
        else out += Block.Table(
            listOf(Column(t("cross.col.episodios"), 0.9f), Column(t("cross.col.comparacion"), 1.6f), Column(t("cross.col.resultado"), 1.5f), Column(t("cross.col.detalle"), 4.7f)),
            v.detail.map { g ->
                val f = g.findings.first()
                listOf(g.episodes.joinToString(", ") { "E$it" }.ifEmpty { "—" }, "${g.check.id}\n${crossCheckLabel(g.check)}\n${crossGroupSources(g)}", crossOutcomeLabel(g.outcome),
                    crossGroupExplanation(g) + " " + crossDetail(f, id, time, maxEntities = 3, maxCompared = 3) + crossRefs(f, 3).let { if (it.isEmpty()) "" else " $it" } +
                        if (g.results > 1) " " + t("cross.group.more_results", int((g.results - 1).toLong())) else "")
            }, t("cross.caption_detail"))
        out += Block.Paragraph(if (ctx.options.crossAnnex) t("cross.annex_included", int(r.findings.size.toLong())) else t("cross.annex_off", int(r.findings.size.toLong())), TextStyle.SMALL)
        val added = ctx.cells.entries.drop(before)
        if (added.isNotEmpty()) out += Block.Paragraph(t("cross.legend", added.joinToString("; ") { (lbl, k) -> "$lbl = ${key(ctx, k)}" }), TextStyle.SMALL)
        return out
    }

    // ───────────────────────── 5. fuentes y calidad ─────────────────────────

    private fun analysisLabel(c: AnalysisCode) = when (c) {
        AnalysisCode.EPISODES -> t("report.analysisLabel.episodios")
        AnalysisCode.SAMPLING -> t("report.analysisLabel.intervalos_sin_registros")
        AnalysisCode.FORENSIC_TIMELINE -> t("report.analysisLabel.cronologia_fina_de_incidentes_caso")
        AnalysisCode.SERVICE_STATE -> t("report.networkContext.estado_de_servicio_radio")
        AnalysisCode.SERVICE_COHERENCE -> t("report.analysisLabel.coherencia_historialradio")
        AnalysisCode.TOPOLOGY -> t("report.analysisLabel.transiciones_agregadas_topologia")
        AnalysisCode.GEOMETRY -> t("report.networkContext.movilidad_geometria")
        AnalysisCode.STABLE_SITE -> t("report.networkContext.lugares_habituales_stable_site")
    }

    private fun availabilityLabel(s: Availability) = when (s) {
        Availability.AVAILABLE -> t("report.availabilityLabel.disponible")
        Availability.NOT_PROVIDED -> t("report.availabilityLabel.no_aportado")
        Availability.NOT_COMPATIBLE -> t("report.availabilityLabel.no_compatible")
        Availability.IMPORT_FAILED -> t("report.availabilityLabel.fallo_la_importacion")
        Availability.NOT_EVALUABLE -> t("report.availabilityLabel.no_evaluable")
    }

    private fun availabilityReason(a: AnalysisAvailability) = when (a.reasonCode) {
        "ZONE_NOT_CONFIRMED" -> t("report.availabilityReason.la_zona_horaria_del_expediente")
        "TOO_FEW_ROWS" -> t("report.availabilityReason.menos_de_dos_filas_con")
        "NO_SIGNAL_SOURCE" -> t("report.availabilityReason.no_hay_historial_caso_forense")
        "RADIO_EMPTY" -> t("report.availabilityReason.la_exportacion_de_radio_no")
        "NO_OVERLAP" -> t("report.availabilityReason.historial_y_radio_no_se")
        else -> when (a.status) {
            Availability.NOT_PROVIDED -> t("report.availabilityReason.no_se_ha_aportado_la")
            Availability.NOT_COMPATIBLE -> t("report.availabilityReason.la_exportacion_aportada_no_tiene", a.files.joinToString())
            Availability.IMPORT_FAILED -> t("report.availabilityReason.no_se_pudo_importar", a.files.joinToString())
            else -> ""
        }
    }

    // ───────────────────────── perfil local (ICdetector 3.0) ─────────────────────────

    private fun profileLevel(l: String) = if (l in com.alexisgordr.cellcase.core.model.ProfileEvaluation.LEVELS) t("profile.level.$l") else l
    private fun profileAbstain(r: String) = if (r in com.alexisgordr.cellcase.core.model.ProfileEvaluation.ABSTAIN_REASONS) t("profile.abstain.$r") else r
    private fun profileLevels(m: Map<String, Int>) = m.entries.joinToString(" · ") { "${profileLevel(it.key)} ${int(it.value)}" }.ifEmpty { "—" }
    private fun profileExtreme(ctx: TextContext, x: com.alexisgordr.cellcase.core.analysis.ProfileExtreme?) = x?.let {
        t("profile.extreme", profileLevel(it.level), it.score?.let { s -> fixed2(s) } ?: "—", it.time?.let { i -> short(ctx, i) } ?: "—", ref(it.provenance)) +
            (it.evidence?.let { e -> " " + t("profile.evidence", e) } ?: "")
    } ?: "—"
    private fun fixed2(x: Double) = String.format(es, "%.2f", x)

    override fun churn(ctx: TextContext, a: CaseAnalysis): List<Block> {
        val c = a.churn ?: return emptyList()
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("churn.title"))
        out += Block.Paragraph(t("rule.H17.what"))
        out += Block.Paragraph(t("rule.H17.meaning"), TextStyle.EMPHASIS)
        val kv = ArrayList<Pair<String, String>>()
        kv += t("churn.k.rows") to t("churn.v.rows", int(c.rowsWithChurn.toLong()), int(c.rowsV31.toLong()), int((a.history?.import?.observations?.size ?: 0).toLong()))
        if (c.first != null) kv += t("churn.k.period") to "${short(ctx, c.first)} – ${short(ctx, c.last ?: c.first)}"
        out += Block.KeyValues(kv)
        out += Block.Table(
            listOf(Column(t("churn.col.motion"), 1.6f), Column(t("report.label.filas"), 0.9f, Align.RIGHT), Column(t("churn.col.cells"), 2.6f), Column(t("churn.col.changes"), 2.6f)),
            c.byMotion.map { s -> listOf(t("churn.motion.${s.motion}"), int(s.rows.toLong()), "${s.cellsMedian} · ${s.cellsP90} · ${s.cellsMax}", "${s.changesMedian} · ${s.changesP90} · ${s.changesMax}") },
            t("churn.caption_stats", com.alexisgordr.cellcase.core.analysis.CHURN_RULES_VERSION))
        if (c.bands.isEmpty()) out += Block.Paragraph(t("churn.bands_none"), TextStyle.SMALL)
        else out += Block.Table(
            listOf(Column(t("churn.col.band"), 2.8f), Column(t("report.label.filas"), 0.8f, Align.RIGHT), Column(t("churn.col.peak"), 1.6f), Column(t("churn.col.motion"), 1.6f), Column(t("churn.col.ref"), 2.6f)),
            c.bands.map { b -> listOf("${short(ctx, b.start)} – ${short(ctx, b.end)}", int(b.rows.toLong()), t("churn.v.peak", b.peakChanges, b.peakCells),
                b.motions.joinToString(", ") { t("churn.motion.$it") }, ref(b.peak)) },
            t("churn.caption_bands", c.changesP90))
        out += Block.Paragraph(t("churn.limits"), TextStyle.SMALL)
        return out
    }

    override fun localProfile(ctx: TextContext, a: CaseAnalysis): List<Block> {
        val p = a.localProfile ?: return emptyList()
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("profile.title"))
        out += Block.Paragraph(t("profile.intro"))
        out += Block.Paragraph(t("profile.context"), TextStyle.EMPHASIS)
        if (p.evaluated == 0) {
            out += Block.Paragraph(t(if (p.rowsV3 == 0) "profile.none_pre30" else "profile.none_evaluated", int(p.rows), int(p.rowsV3)))
            if (p.references.isNotEmpty()) out += Block.Paragraph(t("profile.references", p.references.entries.joinToString(" · ") { "${profileMaturity(it.key)} ${int(it.value)}" }), TextStyle.SMALL)
            return out
        }
        val rows = ArrayList<Pair<String, String>>()
        rows += t("profile.k.evaluated") to t("profile.v.evaluated", int(p.evaluated), int(p.rows), int(p.rowsV3))
        rows += t("profile.k.levels") to profileLevels(p.byLevel)
        if (p.abstainReasons.isNotEmpty()) rows += t("profile.k.abstain") to p.abstainReasons.entries.joinToString("; ") { "${profileAbstain(it.key)} ${int(it.value)}" }
        rows += t("profile.k.mode") to p.modes.entries.joinToString(" · ") { "${t("profile.mode.${it.key}")} ${int(it.value)}" }.ifEmpty { "—" }
        rows += t("profile.k.algorithm") to p.algorithms.entries.joinToString(" · ") { "${it.key} (${int(it.value)})" } + if (p.mixedAlgorithms) " — " + t("profile.mixed") else ""
        rows += t("profile.k.holdout") to int(p.holdout)
        if (p.first != null) rows += t("profile.k.period") to "${short(ctx, p.first)} – ${short(ctx, p.last ?: p.first)}"
        if (p.references.isNotEmpty()) rows += t("profile.k.references") to p.references.entries.joinToString(" · ") { "${profileMaturity(it.key)} ${int(it.value)}" }
        out += Block.KeyValues(rows)
        val eps = p.episodes.filter { it.evaluated > 0 || it.rows > 0 }
        if (eps.isNotEmpty()) out += Block.Table(
            listOf(Column(t("profile.col.episode"), 0.9f), Column(t("profile.col.rows"), 1.3f, Align.RIGHT), Column(t("profile.col.levels"), 3.0f), Column(t("profile.col.extreme"), 4.6f)),
            eps.map { e -> listOf("E${e.episode}", "${int(e.evaluated)} / ${int(e.rows)}", profileLevels(e.byLevel), profileExtreme(ctx, e.extreme)) },
            t("profile.caption", PROFILE_RULES_VERSION))
        out += Block.Paragraph(if (p.deviatedOutsideEpisodes == 0) t("profile.outside_none")
            else t("profile.outside", int(p.deviatedOutsideEpisodes), profileExtreme(ctx, p.extremeOutsideEpisodes)))
        out += Block.Paragraph(t("profile.limits"), TextStyle.SMALL)
        return out
    }

    override fun sourcesAndQuality(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block> {
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("report.sourcesAndQuality.fuentes_y_calidad_de_los"))
        // Origen
        val indicators = ctx.results.filter { it.syntheticIndicators.isNotEmpty() }
        val originText = StringBuilder(t("report.sourcesAndQuality.origen_declarado", originText(ctx.case.origin).lowercase()))
        if (indicators.isNotEmpty()) originText.append(t("report.sourcesAndQuality.indicios_automaticos_de_datos_de", int(indicators.size), indicators.joinToString { it.fileName }, indicators.flatMap { it.syntheticIndicators }.map { it.substringBefore(':') }.distinct().joinToString("; ") { if (it.startsWith("TEST_MCC_001")) t("report.indicatorText.mcc_001") else indicatorText(it) }))
        if (ctx.case.origin == DataOrigin.DECLARED_REAL && indicators.isNotEmpty()) out += Block.Callout(CalloutKind.WARNING, t("report.sourcesAndQuality.origen_en_conflicto"),
            t("report.sourcesAndQuality.el_expediente_esta_declarado_como", int(indicators.size)))
        out += Block.Paragraph(originText.toString())
        // Archivos
        out += Block.Table(
            listOf(Column(t("report.sourcesAndQuality.archivo"), 2.8f), Column(t("report.sourcesAndQuality.tipo"), 1.6f), Column(t("report.networkContext.estado"), 1.5f), Column(t("report.sourcesAndQuality.contenido"), 2.3f), Column(t("report.sourcesAndQuality.periodo"), 2.6f)),
            ctx.results.flatMap { it.withParts() }.map { r -> listOf(r.fileName, kindLabel(r.kind), stateLabel(r.state), if (r.imported) countsText(r) else (r.messageText?.let { tr(it) } ?: "—"), periodText(ctx, r.period)) },
            t("report.sourcesAndQuality.archivos_aportados")
        )
        ctx.results.filter { it.kind == ExportKind.BACKUP }.forEach { out += backupBlocks(ctx, it) }
        val integ = ctx.results.flatMap { it.withParts() }.filter { it.imported && it.integrity != null && it.integrity.status != IntegrityStatus.NOT_PROVIDED }
        if (integ.isNotEmpty()) out += Block.Paragraph(t("report.sourcesAndQuality.integridad_interna") + integ.joinToString("; ") { "${it.fileName}: ${integrityLabel(it)}" } +
            t("report.sourcesAndQuality.sha256sums_solo_detecta_cambios_posteriores"), TextStyle.SMALL)
        a.history?.let { m ->
            if (m.matches.isNotEmpty()) out += Block.Paragraph(
                t("report.sourcesAndQuality.historiales_con_filas_coincidentes_duplicado", MERGE_RULES_VERSION, int(m.exactMatches), int(m.revisedMatches)) +
                    t("report.sourcesAndQuality.mismas_columnas_inmutables_distinta_verificacion") +
                    t("report.sourcesAndQuality.porque_los_archivos_no_acreditan") +
                    (m.matches.count { it.schemaDiffers }.takeIf { it > 0 }?.let { t("report.sourcesAndQuality.par_es_se_compararon_solo", int(it)) } ?: ""), TextStyle.SMALL)
        }
        // Calidad del historial (solo lo que limita el análisis)
        a.historyAnalysis?.let { ha ->
            out += Block.Heading(2, t("report.sourcesAndQuality.historial_calidad_e_interpretacion_temporal"))
            val f = ha.fields.fields.associateBy { it.column }
            fun p(c: String) = f[c]?.let { pct(it.usable, it.total) } ?: "—"
            val sentinels = ha.fields.fields.sumOf { it.count(ValueState.SENTINEL) }
            val oor = ha.fields.fields.sumOf { it.count(ValueState.OUT_OF_RANGE) }
            val taUnits = ha.ta.byUnit.entries.joinToString("; ") { "${noValue(it.key)} ${int(it.value)}" }
            out += Block.KeyValues(listOf(
                t("report.label.filas") to t("report.p.leidas_descartadas_por_estructura_identidades", int(ha.period.totalRows), int(ha.fields.rejectedRows), int(ha.cells.cells.size)),
                t("report.p.campos_validos") to t("report.p.gnss_pci_arfcn_rsrq_sinr", p(t("report.p.lat")), p("PCI"), p("ARFCN"), p("RSRQ"), p("SINR"), p(t("report.p.servicestate"))),
                t("report.p.valores_descartados") to t("report.p.centinelas_n_a_2147483647_y", int(sentinels), int(oor)),
                t("report.p.timing_advance") to t("report.p.solo_lte_index_y_gsm", taUnits),
                t("report.label.horas") to t("report.p.con_instante_utc_unico_ambiguas", int(ha.time.exact), int(ha.time.ambiguous.size), int(ha.time.nonexistent.size)),
                t("report.label.muestreo") to if (ha.sampling.evaluable) t("report.p.mediana_entre_filas_intervalo_s", ha.sampling.medianInterval?.let { dur(it) } ?: "—", int(ha.sampling.gaps.size)) else t("report.p.no_evaluable")
            ))
            out += datasetCutBlocks(ctx, ha)
            out += ruleCoverageBlocks(ha)
        }
        // Análisis no disponibles
        val unavailable = availability.filter { it.status != Availability.AVAILABLE }
        out += Block.Heading(2, t("report.p.analisis_no_disponibles"))
        if (unavailable.isEmpty()) out += Block.Paragraph(t("report.p.todos_los_analisis_previstos_tienen"))
        else out += Block.Table(
            listOf(Column(t("report.p.analisis"), 2.6f), Column(t("report.networkContext.estado"), 1.4f), Column(t("report.p.motivo"), 4.4f)),
            unavailable.map { listOf(analysisLabel(it.code), availabilityLabel(it.status), availabilityReason(it)) },
            t("report.p.analisis_no_disponibles_y_motivo")
        )
        out += Block.Paragraph(t("report.p.no_aportado_falta_la_exportacion"), TextStyle.SMALL)
        return out
    }

    /** Historial con filas anteriores y posteriores a ICdetector 3.1.0: qué no es comparable entre los dos tramos. */
    private fun datasetCutBlocks(ctx: TextContext, ha: HistoryAnalysis): List<Block> {
        val v = ha.versions31
        if (!v.mixed) return emptyList()
        val fields = DatasetCuts.ICD_3_1_0.map { if (it == "LOCAL_CELL_TRUST") t("dataset_cut.local_cell_trust") else it }
        return listOf(Block.Callout(CalloutKind.NOTE, t("dataset_cut.title"),
            t("dataset_cut.text", int(v.rowsBefore.toLong()), v.lastBefore?.let { short(ctx, it) } ?: "—", int(v.rowsFrom31.toLong()),
                v.firstFrom31?.let { short(ctx, it) } ?: "—", fields.dropLast(1).joinToString(", ") + " " + t("dataset_cut.and") + " " + fields.last())))
    }

    /** Reglas que ICdetector no evaluó, separando la falta de datos de las retiradas, en sombra o reservadas. */
    private fun ruleCoverageBlocks(ha: HistoryAnalysis): List<Block> {
        val c = ha.ruleCoverage ?: return emptyList()
        val out = ArrayList<Block>()
        out += Block.Paragraph(t("noteval.summary", int(c.rowsWithDataAbstention.toLong()), int(c.rowsWithField.toLong())) +
            (if (c.unreadable > 0) " " + t("noteval.unreadable", int(c.unreadable.toLong())) else ""), TextStyle.SMALL)
        if (c.byRule.isNotEmpty()) out += Block.Table(
            listOf(Column(t("noteval.col.rule"), 2.4f), Column(t("noteval.col.reason"), 3.6f), Column(t("noteval.col.kind"), 2f), Column(t("report.label.filas"), 0.9f, Align.RIGHT)),
            c.byRule.map { r -> listOf(r.abstention.rule + " " + ruleName(r.abstention.rule), abstentionReason(r.abstention), t("noteval.kind." + r.abstention.kind.name), int(r.rows.toLong())) },
            t("noteval.caption"))
        if (c.of(com.alexisgordr.cellcase.core.analysis.AbstentionKind.RETIRED).isNotEmpty()) out += Block.Paragraph(t("rule.H12.retired"), TextStyle.SMALL)
        return out
    }

    /** Nombre de una regla de ICdetector (catálogo de 3.1.0, sin renumerar); vacío si no se conoce. */
    fun ruleName(rule: String): String = ("rule.$rule.name").let { k -> if (Messages.has(language, k)) t(k) else "" }

    /** Texto del motivo de una regla no evaluada (glosario); el código original va entre paréntesis. */
    fun abstentionReason(a: Abstention): String = when {
        a.reason == null -> t(if (a.rule == "H9") "noteval.reason.reserved_h9" else "noteval.reason.none")
        a.reason in NotEvaluated.KNOWN_REASONS -> t("noteval.reason.${a.reason}") + " (${a.code})"
        else -> a.code
    }

    // ───────────────────────── anexos ─────────────────────────

    override fun annexes(ctx: TextContext, a: CaseAnalysis): List<Block> {
        val out = ArrayList<Block>()
        out += Block.Heading(1, t("report.annexes.anexos"))
        // A. archivos
        out += Block.Heading(2, t("report.annexes.a_archivos_entradas_e_integridad"))
        out += Block.Table(
            listOf(Column(t("report.sourcesAndQuality.archivo"), 2.4f), Column(t("report.annexes.sha_256_del_archivo"), 4.4f, mono = true), Column(t("report.annexes.tamano"), 0.9f, Align.RIGHT, noWrap = true), Column(t("report.annexes.integridad_interna"), 2.2f)),
            ctx.results.flatMap { it.withParts() }.map { listOf(it.fileName, it.sha256, bytes(it.sizeBytes), if (it.imported) integrityLabel(it) else stateLabel(it.state)) },
            t("report.annexes.huella_de_cada_archivo_aportado")
        )
        out += Block.Paragraph(t("report.annexes.sha_256_calculado_por_cellcase"), TextStyle.SMALL)
        val zips = ctx.results.flatMap { it.withParts() }.filter { it.entries.isNotEmpty() }
        if (zips.isNotEmpty()) out += Block.Table(
            listOf(Column(t("report.sourcesAndQuality.archivo"), 2.2f), Column(t("report.annexes.entrada"), 2.2f), Column(t("report.annexes.papel"), 1.6f), Column(t("report.annexes.filas"), 0.8f, Align.RIGHT), Column(t("report.annexes.tamano"), 1f, Align.RIGHT, noWrap = true)),
            zips.flatMap { r -> r.entries.map { e -> listOf(r.fileName, e.name, roleLabel(e.role), e.rows?.let { int(it) } ?: "—", bytes(e.bytes)) } },
            t("report.annexes.entradas_internas_de_los_zip")
        )
        // B. incidencias
        out += Block.Heading(2, t("report.annexes.b_incidencias_de_validacion"))
        val issueRows = ctx.results.flatMap { it.withParts() }.flatMap { r -> r.issues.map { i -> r to i } }
        if (issueRows.isEmpty()) out += Block.Paragraph(t("report.annexes.sin_incidencias"))
        else out += Block.Table(
            listOf(Column(t("report.sourcesAndQuality.archivo"), 2f), Column(t("report.annexes.codigo"), 1.7f, mono = true), Column(t("report.annexes.gravedad"), 0.9f), Column(t("report.annexes.filas"), 0.7f, Align.RIGHT), Column(t("report.annexes.descripcion_y_ejemplos"), 4.6f)),
            issueRows.map { (r, i) ->
                val ex = i.examples.take(3).joinToString("; ") { e -> t("report.annexes.linea", e.provenance?.startLine ?: "-") + (e.rawValue?.let { v -> " «${v.take(40)}»" } ?: "") + (e.note?.let { " (" + tr(it) + ")" } ?: "") }
                listOf(r.fileName, i.code, sev(i.severity), int(i.count), tr(i.text) + if (ex.isNotEmpty()) t("report.annexes.ej", ex) else "")
            },
            t("report.annexes.incidencias_agrupadas_por_archivo")
        )
        a.history?.matches?.takeIf { it.isNotEmpty() }?.let { m ->
            val shown = m.sortedByDescending { it.kind.ordinal }.take(30)
            out += Block.Table(listOf(Column(t("report.annexes.fila"), 2.6f), Column(t("report.annexes.fila_coincidente"), 2.6f), Column(t("report.sourcesAndQuality.tipo"), 1f), Column(t("report.annexes.valores_en_conflicto_archivo_1"), 3.6f)),
                shown.map { c -> listOf(ref(c.kept), ref(c.other), if (c.kind == RowMatch.EXACT) t("report.annexes.exacto") else t("report.annexes.revision"),
                    c.fields.entries.joinToString("; ") { "${it.key}: ${it.value.first ?: "—"} / ${it.value.second ?: "—"}" }.ifEmpty { "—" } + if (c.schemaDiffers) t("report.annexes.esquemas_distintos") else "") },
                t("report.annexes.filas_coincidentes_entre_historiales_de", int(shown.size), int(m.size)))
        }
        // C. evidencias por episodio
        out += Block.Heading(2, t("report.annexes.c_referencias_de_evidencia_por"))
        out += Block.Paragraph(t("report.annexes.cada_fila_se_identifica_por") +
            if (ctx.options.coordinates == CoordinateMode.OMIT) t("report.annexes.coordenadas_omitidas_segun_las_opciones") else t("report.annexes.coordenadas_con_redondeo_visual_anexo"), TextStyle.SMALL)
        for (e in a.episodes.episodes) {
            // Evidencia clave: filas con señal y la fila anterior a cada una (la que permite ver el cambio).
            val sigRows = e.signals.mapNotNull { it.row }
            val ordered = (e.before + e.during + e.after)
            val prevRows = sigRows.mapNotNull { s -> ordered.lastOrNull { it.time.instantOrNull!!.isBefore(s.time.instantOrNull) && it.provenance !in sigRows.map { r -> r.provenance } } }
            val rows = (sigRows + prevRows).distinctBy { it.provenance }.sortedBy { it.time.instantOrNull }
            val others = e.signals.filter { it.row == null }
            val withPos = ctx.options.coordinates != CoordinateMode.OMIT
            val cols = mutableListOf(Column(t("report.annexes.referencia"), 2.3f), Column(t("report.annexes.hora_local"), 1.6f, noWrap = true), Column(t("report.cellLegend.identidad"), 2.6f, mono = true, noWrap = true), Column(t("report.annexes.dbm"), 0.6f, Align.RIGHT), Column(t("report.annexes.motivo_de_icdetector"), 2.8f))
            if (withPos) cols += Column(t("report.annexes.posicion"), 1.9f, noWrap = true)
            val shown = rows.take(ctx.options.maxEvidenceRows)
            val tableRows = shown.map { o ->
                listOfNotNull(ref(o.provenance), o.time.local?.let { local(it) } ?: o.time.raw, idText(ctx, o.identity), o.dbm?.toString() ?: "—",
                    (o.failedHeuristics ?: "").take(160), if (withPos) Privacy.coordinate(o.lat, o.lon, ctx.options.coordinates) ?: "—" else null)
            } + others.map { s ->
                listOfNotNull(s.refs.joinToString("; ") { ref(it) }, at(ctx, s.start), idText(ctx, s.identity), "—", "${sourceLabel(s.source)}: ${s.text.take(80)}", if (withPos) "—" else null)
            }
            out += Block.Table(cols, tableRows, t("report.annexes.e_filas_con_senal_y", e.number) + (if (rows.size > shown.size) t("report.annexes.de", int(shown.size), int(rows.size)) else "") +
                t("report.annexes.el_resto_del_intervalo_filas", int(e.before.size + e.during.size + e.after.size)))
        }
        // D. descripción del historial
        a.historyAnalysis?.let { out += historyAnnex(ctx, it) }
        // E. reglas
        out += Block.Heading(2, t("report.annexes.e_reglas_y_umbrales"))
        out += Block.Bullets(listOf(
            t("report.annexes.agrupacion_misma_celda_y_mismo", EpisodeEngine.SAME_IDENTITY_GAP.toMinutes(), EpisodeEngine.GROUP_GAP.toMinutes(), EpisodeEngine.SERVICE_LINK.toMinutes()),
            t("report.annexes.momento_de_observacion_senales_a", EpisodeEngine.SAME_MOMENT.seconds, EpisodeEngine.SUSTAINED_MOMENTS, EpisodeEngine.SUSTAINED_SPAN.toMinutes()),
            t("report.annexes.banda_lte_columna_bands_o", EpisodeEngine.PREVIOUS_ROW_MAX.toMinutes()),
            t("report.annexes.potencia_mediana_de_la_misma", EpisodeEngine.BASELINE_MIN_ROWS, EpisodeEngine.POWER_DIFF_DB),
            t("report.annexes.familias_de_evidencia_colector_historial"),
            t("report.annexes.intervalo_sin_registros_60_min"),
            t("report.annexes.versiones", RULESET_VERSION, EPISODE_RULES_VERSION, READERS_VERSION, CROSS_RULES_VERSION)
        ), TextStyle.SMALL)
        // F. privacidad
        out += Block.Heading(2, t("report.annexes.f_privacidad_y_redondeos"))
        out += Block.Bullets(listOf(
            t("report.annexes.coordenadas") + when (ctx.options.coordinates) {
                CoordinateMode.OMIT -> t("report.annexes.omitidas_en_todo_el_informe")
                CoordinateMode.ROUND_2 -> t("report.annexes.redondeadas_a_2_decimales_del")
                CoordinateMode.ROUND_3 -> t("report.annexes.redondeadas_a_3_decimales_del")
                CoordinateMode.FULL -> t("report.annexes.mostradas_con_decimales_redondeo_visual", Privacy.FULL_DISPLAY_DECIMALS)
            },
            t("report.annexes.identificadores_de_celda") + if (ctx.options.identifiers == IdentifierMode.MASKED) t("report.annexes.tac_y_cid_enmascarados_salvo") else t("report.annexes.completos"),
            t("report.annexes.potencias_y_porcentajes_se_muestran"),
            t("report.annexes.los_archivos_originales_conservados_en")
        ))
        // G. listado completo de cruces: anexo opcional, desactivado por defecto.
        val cr = a.cross
        if (ctx.options.crossAnnex && cr != null && cr.findings.isNotEmpty()) {
            out += Block.Heading(2, t("cross.annex_title"))
            out += Block.Paragraph(crossCounts(cr) + " " + t("cross.totals_note"), TextStyle.SMALL)
            val before = ctx.cells.entries.size
            out += crossFullTable(ctx, cr)
            val added = ctx.cells.entries.drop(before)
            if (added.isNotEmpty()) out += Block.Paragraph(t("cross.legend", added.joinToString("; ") { (lbl, k) -> "$lbl = ${key(ctx, k)}" }), TextStyle.SMALL)
        }
        return out
    }

    fun roleLabel(r: EntryRole) = when (r) {
        EntryRole.DATA -> t("report.roleLabel.datos")
        EntryRole.METADATA -> t("report.roleLabel.metadatos")
        EntryRole.INTEGRITY -> t("report.roleLabel.integridad")
        EntryRole.CONSISTENCY -> t("report.roleLabel.comprobacion")
        EntryRole.CONTEXT -> t("report.priorityLabel.contexto")
        EntryRole.UNKNOWN -> t("report.roleLabel.no_interpretada")
    }

    private fun sev(s: Severity) = when (s) { Severity.ERROR -> t("report.sev.error"); Severity.WARNING -> t("report.sev.aviso"); Severity.INFO -> t("report.sev.info") }

    private fun historyAnnex(ctx: TextContext, ha: HistoryAnalysis): List<Block> {
        val out = ArrayList<Block>()
        out += Block.Heading(2, t("report.historyAnnex.d_descripcion_estadistica_del_historial"))
        val c = ha.cells
        out += Block.Table(
            listOf(Column(t("report.cellLegend.identidad"), 3.3f, mono = true, noWrap = true), Column(t("report.annexes.filas"), 0.8f, Align.RIGHT), Column(t("report.historyAnnex.filas"), 0.9f, Align.RIGHT, noWrap = true),
                Column(t("report.historyAnnex.primera_local"), 1.9f, noWrap = true), Column(t("report.historyAnnex.ultima_local"), 1.9f, noWrap = true)),
            c.cells.take(15).map { cc -> listOf(key(ctx, cc.key), int(cc.rows), pct(cc.rows, ha.period.totalRows),
                cc.first.time.local?.let { local(it) } ?: "—", cc.last.time.local?.let { local(it) } ?: "—") },
            t("report.historyAnnex.identidades_servidoras_con_mas_filas", int(minOf(15, c.cells.size)), int(c.cells.size))
        )
        if (ha.power.stats.isNotEmpty()) out += Block.Table(
            listOf(Column(t("report.historyAnnex.tecnologia"), 1f), Column(t("report.historyAnnex.magnitud"), 3f), Column("n", 0.8f, Align.RIGHT), Column(t("report.historyAnnex.min"), 0.8f, Align.RIGHT), Column("P10", 0.8f, Align.RIGHT),
                Column(t("report.historyAnnex.mediana"), 1f, Align.RIGHT), Column("P90", 0.8f, Align.RIGHT), Column(t("report.historyAnnex.max"), 0.8f, Align.RIGHT)),
            ha.power.stats.map { s -> listOf(s.radio.name, magnitude(s.radio), int(s.n), "${s.min}", "${s.p10}", "${s.median}", "${s.p90}", "${s.max}") },
            t("report.historyAnnex.potencia_registrada_dbm_por_tecnologia")
        )
        val v = ha.collector.verdicts
        out += Block.KeyValues(listOf(
            t("report.historyAnnex.resultados_de_icdetector") to t("report.historyAnnex.ok_sub_umbral_motivo_sin", int(v[CollectorVerdict.OK] ?: 0), int(v[CollectorVerdict.SUBTHRESHOLD] ?: 0), int(v[CollectorVerdict.REASON_WITHOUT_PREFIX] ?: 0), int(ha.collector.scoreBelow100)),
            "AnomalyConfidence" to t("report.historyAnnex.maximo_puntuacion_interna_no_calibrada", ha.collector.maxAnomalyConfidence?.let { String.format(es, "%.1f", it) } ?: "—"),
            t("report.historyAnnex.verificacion_externa") to (ha.collector.verified.entries.map { "${noValue(it.key)} ${int(it.value)}" } +
                if (ha.collector.verificationRetired > 0) listOf(t("report.historyAnnex.verification_retired", int(ha.collector.verificationRetired.toLong()))) else emptyList()).joinToString("; "),
            t("report.historyAnnex.coherencia_de_ta") to t("report.historyAnnex.fila_s_con_tameters_distinto", int(ha.ta.incoherent.size)),
            t("report.historyAnnex.duplicados_en_el_archivo") to t("report.historyAnnex.identicas_con_misma_hora_e", int(ha.duplicates.exactPairs.size), int(ha.duplicates.sameTimeIdentity.size)),
            t("report.historyAnnex.intervalo_sin_registros") to t("report.historyAnnex.umbral_min", HistoryAnalyzer.GAP_THRESHOLD.toMinutes())
        ))
        return out
    }

    @Suppress("unused") private fun date(d: LocalDate) = DATE.format(d)
}

/** Informe en español. */
object ReportTextsEs : ReportTextsImpl(Language.ES)

/** Informe en inglés. */
object ReportTextsEn : ReportTextsImpl(Language.EN)

fun reportTexts(lang: Language): ReportTextsImpl = when (lang) { Language.ES -> ReportTextsEs; Language.EN -> ReportTextsEn }

/**
 * Pone en minúscula la primera letra para seguir una frase, salvo que la primera palabra sea una sigla
 * (PCI, LTE, TAC…): «PCI» no debe convertirse en «pCI».
 */
internal fun String.lowerFirst(): String =
    if (length > 1 && this[0].isUpperCase() && (this[1].isUpperCase() || this[1].isDigit())) this
    else replaceFirstChar { it.lowercase() }
