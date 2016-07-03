/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2014 LensKit Contributors.  See CONTRIBUTORS.md.
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.lenskit.gradle

import com.google.common.io.Files
import groovy.json.JsonOutput
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFiles
import org.gradle.util.ConfigureUtil
import org.lenskit.gradle.delegates.DataSetConfig
import org.lenskit.gradle.delegates.EvalTaskConfig
import org.lenskit.gradle.delegates.RecommendEvalTaskConfig
import org.lenskit.specs.eval.DataSetSpec
import org.lenskit.specs.eval.PredictEvalTaskSpec

import java.util.concurrent.Callable

/**
 * Run a train-test evaluation.
 */
class TrainTest extends LenskitTask {
    /**
     * The output file for recommendation output.
     */
    def outputFile

    /**
     * The user output file for recommendation output.
     */
    def userOutputFile

    /**
     * The cache directory for the recommender.
     */
    def cacheDirectory

    /**
     * The thread count for the evaluator.
     */
    def int threadCount

    /**
     * Configure whether the evaluator should share model components between algorithms.
     */
    def boolean shareModelComponents = true

    private Map<String,Object> algorithms = new HashMap<>()
    private List<Callable> dataSets = []
    private List<EvalTaskConfig> tasks = []

    /**
     * The file name for writing out the experiment description. Do not change unless absolutely necessary.
     */
    def File specFile

    public TrainTest() {
        conventionMapping.threadCount = {
            project.lenskit.threadCount
        }
        conventionMapping.outputFile = {
            "$project.buildDir/${name}.csv"
        }
        conventionMapping.specFile = {
            project.file("$project.buildDir/${name}-spec.json")
        }
    }

    /**
     * Add a data set.
     * @param ds The data set configuration to add.
     */
    void dataSet(Map ds) {
        dataSets.add {ds}
    }

    /**
     * Add a data set.
     * @param ds The file of the data set to add.
     */
    void dataSet(Object ds) {
        inputs.file ds
        dataSets.add({project.file(ds)})
    }

    /**
     * Add a data sets produced by a crossfold task.
     *
     * @param ds The crossfold tasks to add.
     */
    def dataSet(DataSetProvider cf) {
        dataSet(Collections.emptyMap(), cf)
    }

    /**
     * Configure a train-test data set.
     * @param block A block which will be used to configureSpec a {@link DataSetSpec}.
     */
    void dataSet(@DelegatesTo(DataSetConfig) Closure block) {
        def set = new DataSetConfig(project)
        ConfigureUtil.configure(block, set)
        dataSets.add({[name: set.name, test: set.testSource, train: set.trainSource]})
    }

    /**
     * Add a data sets produced by a crossfold task or other data set provider.
     *
     * <p>This method supports options for adding the crossfolded data sets:
     *
     * <dl>
     *     <dt>isolate</dt>
     *     <dd>If {@code true}, isolates each of the data sets from each other and from other data sets by assigning
     *     each a random isolation group ID.</dd>
     * </dl>
     *
     * @param options Options for adding the data sets.
     * @param ds The crossfold tasks to add.
     */
    def dataSet(Map<String,Object> options, DataSetProvider cf) {
        inputs.files cf
        if (options.isolate) {
            throw new UnsupportedOperationException("isolation not currently supported")
        } else {
            dataSets.add {
                cf.dataSetFile
            }
        }
    }

    /**
     * Load one or more algorithms from a file.
     * @param name The algorithm name.
     * @param file The file.
     */
    void algorithm(String name, file) {
        algorithms[name ?: Files.getNameWithoutExtension(file)] = file
        inputs.file file
    }

    /**
     * Load one or more algorithms from a file.
     * @param file The algorithm file
     */
    void algorithm(file) {
        algorithm(null, file)
    }

    /**
     * Configure a prediction task.
     * @param block The block.
     * @see PredictEvalTaskSpec
     */
    void predict(@DelegatesTo(EvalTaskConfig) Closure block) {
        def task = new EvalTaskConfig(project, 'predict')
        task.configure block
        tasks.add(task)
    }

    /**
     * Configure a prediction task.
     * @param block The block.
     * @see PredictEvalTaskSpec
     */
    void recommend(@DelegatesTo(RecommendEvalTaskConfig) Closure block) {
        def task = new RecommendEvalTaskConfig(project)
        task.configure block
        tasks.add(task)
    }

    @Input
    def getJson() {
        def json = [output_file: getOutputFile(),
                    user_output_file: getUserOutputFile(),
                    cache_directory: getCacheDirectory(),
                    thread_count: getThreadCount(),
                    share_model_components: getShareModelComponents()]
        json.datasets = dataSets.collect {it.call()}
        json.algorithms = algorithms.collectEntries { k, v ->
            [k, project.file(v)]
        }
        json.tasks = tasks.collect({it.json})

        return json
    }

    @Override
    String getCommand() {
        'train-test'
    }

    @OutputFiles
    public Set<File> getOutputFiles() {
        Set files = [outputFile, userOutputFile].findAll().collect { project.file(it) }
        files.addAll(tasks.collect({it.outputFile}).findAll().collect {
            project.file(it)
        })
        return files
    }

    @Override
    void doPrepare() {
        def file = getSpecFile()
        project.mkdir file.parentFile
        logger.info 'preparing spec file {}', file
        file.text = JsonOutput.prettyPrint(JsonOutput.toJson(json))
    }

    @Override
    List getCommandArgs() {
        def args = []
        args << getSpecFile()
    }
}
