package io.kopipes.aws.java

import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.metrics.EmfReporter
import io.kopipes.core.UnitOfWork
import io.kopipes.core.metrics.MetricsReporter
import io.kopipes.core.metrics.PipelineMetrics
import io.kopipes.core.metrics.collectMetrics
import io.kopipes.core.metrics.updateMetrics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.function.Function

/** Utility class providing Java-friendly methods for common pipeline creation. */
object Handlers {

    /**
     * Reports the batch's metrics through an [EmfReporter] built from [context]'s config, which writes them when
     * `METRICS` includes `emf`. Use it as a transformer: `.transformer(flow -> Handlers.collectMetrics(flow, context))`.
     */
    @JvmStatic
    fun collectMetrics(flow: Flow<UnitOfWork>, context: AwsKopipesContext): Flow<UnitOfWork> =
        flow.collectMetrics(reporter = EmfReporter(context.envConfig))

    /** Reports the batch's metrics, plus [functionMetrics], through an [EmfReporter] built from [context]'s config. */
    @JvmStatic
    fun collectMetrics(
        flow: Flow<UnitOfWork>,
        functionMetrics: Map<String, Any>,
        context: AwsKopipesContext,
    ): Flow<UnitOfWork> =
        flow.collectMetrics(functionMetrics, EmfReporter(context.envConfig))

    /** Reports the batch's metrics through [reporter]. */
    @JvmStatic
    fun collectMetrics(flow: Flow<UnitOfWork>, reporter: MetricsReporter): Flow<UnitOfWork> =
        flow.collectMetrics(reporter = reporter)

    /** Reports the batch's metrics, plus [functionMetrics], through [reporter]. */
    @JvmStatic
    fun collectMetrics(
        flow: Flow<UnitOfWork>,
        functionMetrics: Map<String, Any>,
        reporter: MetricsReporter,
    ): Flow<UnitOfWork> =
        flow.collectMetrics(functionMetrics, reporter)

    @Deprecated(
        "Calculates the metrics but reports them nowhere. Pass the context or a reporter.",
        ReplaceWith("collectMetrics(flow, context)"),
    )
    @JvmStatic
    fun collectMetrics(flow: Flow<UnitOfWork>): Flow<UnitOfWork> =
        flow.collectMetrics()

    @Deprecated(
        "Calculates the metrics but reports them nowhere. Pass the context or a reporter.",
        ReplaceWith("collectMetrics(flow, functionMetrics, context)"),
    )
    @JvmStatic
    fun collectMetrics(flow: Flow<UnitOfWork>, functionMetrics: Map<String, Any>): Flow<UnitOfWork> =
        flow.collectMetrics(functionMetrics)

    @JvmStatic
    fun updateMetrics(flow: Flow<UnitOfWork>, transform: Function<PipelineMetrics, PipelineMetrics>): Flow<UnitOfWork> =
        flow.map { uow -> uow.updateMetrics { pm -> transform.apply(pm) } }
}
