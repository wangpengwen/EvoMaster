package org.evomaster.core.problem.rest.resource.service

import ch.qos.logback.core.db.dialect.DBUtil
import com.google.inject.Inject
import org.apache.commons.lang3.mutable.Mutable
import org.evomaster.client.java.controller.api.dto.TestResultsDto
import org.evomaster.client.java.controller.api.dto.database.operations.DataRowDto
import org.evomaster.core.EMConfig
import org.evomaster.core.database.DbAction
import org.evomaster.core.database.DbActionUtils
import org.evomaster.core.problem.rest.HttpVerb
import org.evomaster.core.problem.rest.RestAction
import org.evomaster.core.problem.rest.RestCallAction
import org.evomaster.core.problem.rest.SampleType
import org.evomaster.core.problem.rest.auth.AuthenticationInfo
import org.evomaster.core.problem.rest.param.BodyParam
import org.evomaster.core.problem.rest.param.Param
import org.evomaster.core.problem.rest.param.PathParam
import org.evomaster.core.problem.rest.param.QueryParam
import org.evomaster.core.problem.rest.resource.ResourceRestIndividual
import org.evomaster.core.problem.rest.resource.model.RestResource
import org.evomaster.core.problem.rest.resource.model.ResourceRestCalls
import org.evomaster.core.problem.rest.resource.model.dependency.MutualResourcesRelations
import org.evomaster.core.problem.rest.resource.model.dependency.ParamRelatedToTable
import org.evomaster.core.problem.rest.resource.model.dependency.ResourceRelatedToResources
import org.evomaster.core.problem.rest.resource.model.dependency.SelfResourcesRelation
import org.evomaster.core.problem.rest.resource.util.*
import org.evomaster.core.search.Action
import org.evomaster.core.search.EvaluatedIndividual
import org.evomaster.core.search.gene.*
import org.evomaster.core.search.service.Randomness
import org.evomaster.core.search.service.Sampler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import javax.swing.text.html.parser.Parser
import kotlin.math.max

/**
 * the class is used to manage all resources
 */
class ResourceManageService {

    companion object {
        val log: Logger = LoggerFactory.getLogger(ResourceManageService::class.java)
    }

    @Inject
    private lateinit var sampler: Sampler<*>

    @Inject
    private lateinit var randomness: Randomness

    @Inject
    private lateinit var config: EMConfig

    /**
     * key is resource path
     * value is an abstract resource
     */
    private val resourceCluster : MutableMap<String, RestResource> = mutableMapOf()

    /**
     * key is resource path
     * value is a list of tables that are related to the resource
     */
    private val resourceTables : MutableMap<String, MutableSet<String>> = mutableMapOf()

    /**
     * key is table name
     * value is a list of existing data of PKs in DB
     */
    private val dataInDB : MutableMap<String, MutableList<DataRowDto>> = mutableMapOf()

    /**
     * key is either a path of one resource, or a list of paths of resources
     * value is a list of related to resources
     */
    private val dependencies : MutableMap<String, MutableList<ResourceRelatedToResources>> = mutableMapOf()

    /**
     * key is a path of an resource
     * value is a set of resources that is not related to the key, i.e., the key does not rely on
     */
    private val nondependencies : MutableMap<String, MutableSet<String>> = mutableMapOf()

    private var flagInitDep = false

    fun initAbstractResources(actionCluster : MutableMap<String, Action>) {
        if(hasDBHandler())
            snapshotDB()

        actionCluster.values.forEach { u ->
            if (u is RestCallAction) {
                val resource = resourceCluster.getOrPut(u.path.toString()) {
                    RestResource(u.path.copy(), mutableListOf()).also {
                        if (config.doesApplyTokenParser)
                            it.initTokens(dataInDB.keys)
                    }
                }
                resource.actions.add(u)
            }
        }
        resourceCluster.values.forEach{it.initAncestors(getResourceCluster().values.toList())}

        resourceCluster.values.forEach{it.init()}

        if(hasDBHandler()){
            /*
                derive possible db creation for each abstract resources.
                The derived db creation needs to be further confirmed based on feedback from evomaster driver (NOT IMPLEMENTED YET)
             */
            resourceCluster.values.forEach {ar->
                if(ar.paramsToTables.isEmpty() && config.doesApplyTokenParser)
                    deriveRelatedTables(ar,false)
            }
        }

        if(config.doesApplyTokenParser)
            initDependency()

    }

    /**
     * [resourceTables] and [RestResource.paramsToTables] are basic ingredients for an initialization of [dependencies]
     * thus, the starting point to invoke [initDependency] depends on when the ingredients are ready.
     *
     * if [EMConfig.doesApplyTokenParser] the invocation happens when init resource cluster,
     * else the invocation happens when all ad-hoc individuals are executed
     *
     * Note that it can only be executed one time
     */
    fun initDependency(){
        if(flagInitDep) return
        flagInitDep = true

//        initDependencyBasedOnResourceTables()
        initDependencyBasedOnTokens()

//        initDependencyBasedOnParamRelatedTables()
        initDependencyBasedOnSchema()


    }

    private fun initDependencyBasedOnResourceTables(){
        resourceTables.values.flatten().toSet().forEach { tab->
            val mutualResources = resourceTables.filter { it.value.contains(tab) }.map { it.key }.toHashSet().toList()

            if(mutualResources.isNotEmpty() && mutualResources.size > 1){
                val mutualRelation = MutualResourcesRelations(mutualResources, 1.0, mutableSetOf(tab))

                mutualResources.forEach { res ->
                    val relations = dependencies.getOrPut(res){ mutableListOf()}

                    relations.find { r-> r is MutualResourcesRelations && r.targets.contains(mutualRelation.targets)}.let {
                        if(it == null)
                            relations.add(mutualRelation)
                        else
                            (it as MutualResourcesRelations).referredTables.add(tab)

                    }
                }
            }
        }
    }

    private fun initDependencyBasedOnTokens(){
        dataInDB.keys.forEach { table->
            val mutualResources = resourceCluster.filter { r -> r.value.getDerivedRelatedTables().any { e -> DbUtil.formatTableName(e) == DbUtil.formatTableName(table)}}.keys.toList()
            if(mutualResources.isNotEmpty() && mutualResources.size > 1){
                val mutualRelation = MutualResourcesRelations(mutualResources, ParserUtil.SimilarityThreshold, mutableSetOf(DbUtil.formatTableName(table)))

                mutualResources.forEach { res ->
                    val relations = dependencies.getOrPut(res){ mutableListOf()}
                    relations.find { r-> r is MutualResourcesRelations && r.targets.contains(mutualRelation.targets)}.let {
                        if(it == null)
                            relations.add(mutualRelation)
                        else
                            (it as MutualResourcesRelations).referredTables.add(DbUtil.formatTableName(table))
                    }
                }
            }
        }
    }



    private fun initDependencyBasedOnSchema(){
        resourceCluster.values
                .filter { it.actions.filter { it is RestCallAction && it.verb == HttpVerb.POST }.isNotEmpty() }
                .forEach { r->
                    /*
                     TODO Man should only apply on POST Action? how about others?
                     */
                    val post = r.actions.first { it is RestCallAction && it.verb == HttpVerb.POST }!! as RestCallAction
                    post.tokens.forEach { _, u ->
                        resourceCluster.values.forEach { or ->
                            if(or != r){
                                or.actions
                                        .filter { it is RestCallAction }
                                        .flatMap { (it as RestCallAction).tokens.values.filter { t -> t.fromDefinition && t.isDirect && t.isType } }
                                        .filter{ot ->
                                            ParserUtil.stringSimilarityScore(u.getKey(), ot.getKey()) >= ParserUtil.SimilarityThreshold
                                        }.let {
                                            if(it.isNotEmpty()){
                                                val addInfo = it.map { t-> t.getKey()}.joinToString(";")
                                                updateDependencies(or.getName(), mutableListOf(r.getName()), addInfo, ParserUtil.SimilarityThreshold)
                                                updateDependencies(r.getName(), mutableListOf(or.getName()), addInfo, ParserUtil.SimilarityThreshold)
                                            }

                                        }

                            }
                        }
                    }
                }
    }


    /**
     * detect possible dependency among resources,
     * the entry is structure mutation
     *
     * [isBetter] 1 means current is better than previous, 0 means that they are equal, and -1 means current is worse than previous
     */
    fun detectDependency(previous : EvaluatedIndividual<ResourceRestIndividual>, current : EvaluatedIndividual<ResourceRestIndividual>, isBetter: Int){
        val seqPre = previous.individual.getResourceCalls()
        val seqCur = current.individual.getResourceCalls()

        when(seqCur.size - seqPre.size){
            0 ->{
                //SWAP, MODIFY, REPLACE are on the category
                if(seqPre.map { it.resourceInstance.getAResourceKey() }.toList() == seqCur.map { it.resourceInstance.getAResourceKey() }.toList()){
                    //MODIFY
                    /*
                        For instance, ABCDEFG, if we replace B with another resource instance, then check CDEFG.
                        if C is worse/better, C rely on B, else C may not rely on B, i.e., the changes of B cannot affect C.
                     */
                    if(isBetter != 0){
                        val locOfModified = (0 until seqCur.size).find { seqPre[it].template.template != seqCur[it].template.template }?:
                            return
                        //throw IllegalArgumentException("mutator does not change anything.")

                        val modified = seqCur[locOfModified]
                        val distance = seqCur[locOfModified].actions.size - seqPre[locOfModified].actions.size

                        var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                            if(index <= locOfModified) restResourceCalls.actions.size
                            else 0
                        }.sum()

                        ((locOfModified + 1) until seqCur.size).forEach { indexOfCalls ->
                            var isAnyChange = false
                            seqCur[indexOfCalls].actions.forEach {curAction ->
                                val actionA = actionIndex - distance
                                isAnyChange = isAnyChange || ComparisionUtil.compare(actionIndex, current, actionA, previous) !=0
                                actionIndex += 1
                            }

                            if(isAnyChange){
                                val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                                updateDependencies(seqKey, mutableListOf(modified!!.resourceInstance.getAResourceKey()), ResourceRestStructureMutator.MutationType.MODIFY.toString())
                            }
                        }
                    }


                }else if(seqPre.map { it.resourceInstance.getAResourceKey() }.toSet() == seqCur.map { it.resourceInstance.getAResourceKey() }.toSet()){
                    //SWAP
                    /*
                        For instance, ABCDEFG, if we swap B and F, become AFCDEBG, then check FCDE (do not include B!).
                        if F is worse, F may rely on {C, D, E, B}
                        if C is worse, C rely on B; else if C is better, C rely on F; else C may not rely on B and F

                        there is another case regarding duplicated resources calls (i.e., same resource and same actions) in a test,
                        for instance, ABCDB*B**EF, swap B and F, become AFCDB*B**EB, in this case,
                        B* probability become better, B** is same, B probability become worse
                     */
                    //find the element is not in the same position
                    val swapsloc = mutableListOf<Int>()

                    seqCur.forEachIndexed { index, restResourceCalls ->
                        if(restResourceCalls.resourceInstance.getKey() != seqPre[index].resourceInstance.getKey())
                            swapsloc.add(index)
                    }

                    assert(swapsloc.size == 2)
                    val swapF = seqCur[swapsloc[0]]
                    val swapB = seqCur[swapsloc[1]]

                    if(isBetter != 0){
                        val locOfF = swapsloc[0]
                        val distance = swapF.actions.size - swapB.actions.size

                        //check F
                        val middles = seqCur.subList(swapsloc[0]+1, swapsloc[1]+1).map { it.resourceInstance.getAResourceKey() }
                        if(ComparisionUtil.compare(swapsloc[0], current, swapsloc[1], previous) != 0){
                            middles.forEach {
                                updateDependencies(swapF.resourceInstance.getAResourceKey(), mutableListOf(it),ResourceRestStructureMutator.MutationType.SWAP.toString(), (1.0/middles.size))
                            }
                        }else{
                            nondependencies.getOrPut(swapF.resourceInstance.getAResourceKey()){ mutableSetOf()}.apply {
                                addAll(middles.toHashSet())
                            }
                        }

                        //check FCDE
                        var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                            if(index <= locOfF) restResourceCalls.actions.size
                            else 0
                        }.sum()

                        ( (locOfF + 1) until swapsloc[1] ).forEach { indexOfCalls ->
                            var isAnyChange = false
                            var changeDegree = 0

                            seqCur[indexOfCalls].actions.forEach {curAction->
                                val actionA = actionIndex - distance

                                val compareResult = swapF.actions.plus(swapB.actions).find { it.getName() == curAction.getName() }.run {
                                    if(this == null) ComparisionUtil.compare(actionIndex, current, actionA, previous)
                                    else ComparisionUtil.compare(this.getName(), current, previous)
                                }.also { r-> changeDegree += r }

                                isAnyChange = isAnyChange || compareResult!=0
                                actionIndex += 1
                                //isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous).also { r-> changeDegree += r } !=0
                            }

                            val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                            if(isAnyChange){

                                val relyOn = if(changeDegree > 0){
                                    mutableListOf(swapF!!.resourceInstance.getAResourceKey())
                                }else if(changeDegree < 0){
                                    mutableListOf(swapB!!.resourceInstance.getAResourceKey())
                                }else
                                    mutableListOf(swapB!!.resourceInstance.getAResourceKey(), swapF!!.resourceInstance.getAResourceKey())

                                updateDependencies(seqKey, relyOn, ResourceRestStructureMutator.MutationType.SWAP.toString())
                            }else{
                                nondependencies.getOrPut(seqKey){ mutableSetOf()}.apply {
                                    add(swapB.resourceInstance.getAResourceKey())
                                    add(swapF.resourceInstance.getAResourceKey())
                                }
                            }
                        }

                        val before = seqCur.subList(swapsloc[0], swapsloc[1]).map { it.resourceInstance.getAResourceKey() }
                        if(ComparisionUtil.compare(swapsloc[1], current, swapsloc[0], previous) != 0){
                            middles.forEach {
                                updateDependencies(swapB.resourceInstance.getAResourceKey(), mutableListOf(it),ResourceRestStructureMutator.MutationType.SWAP.toString(), (1.0/before.size))
                            }
                        }else{
                            nondependencies.getOrPut(swapB.resourceInstance.getAResourceKey()){ mutableSetOf()}.addAll(before)
                        }

                        //TODO check G, a bit complicated,

                    }else{
                        /*
                            For instance, ABCDEFG, if we swap B and F, become AFCDEBG.
                            if there is no any impact on fitness,
                                1) it probably means {C,D,E} does not rely on B and F
                                2) F does not rely on {C, D, E}
                                3) F does not rely on B
                         */
                        val middles = seqCur.subList(swapsloc[0]+1, swapsloc[1]+1).map { it.resourceInstance.getAResourceKey() }
                        middles.forEach { c->
                            nondependencies.getOrPut(c){ mutableSetOf()}.apply {
                                add(swapB.resourceInstance.getAResourceKey())
                                add(swapF.resourceInstance.getAResourceKey())
                            }
                            nondependencies.getOrPut(swapF.resourceInstance.getAResourceKey()){ mutableSetOf()}.add(c)
                        }
                        nondependencies.getOrPut(swapF.resourceInstance.getAResourceKey()){ mutableSetOf()}.add(swapB.resourceInstance.getAResourceKey())
                    }

                }else{
                    //REPLACE
                    /*
                        For instance, ABCDEFG, if we replace B with H become AHCDEFG, then check CDEFG.
                        if C is worse, C rely on B; else if C is better, C rely on H; else C may not rely on B and H

                     */

                    val mutatedIndex = (0 until seqCur.size).find { seqCur[it].resourceInstance.getKey() != seqPre[it].resourceInstance.getKey() }!!

                    val replaced = seqCur[mutatedIndex]!!
                    val replace = seqPre[mutatedIndex]!!

                    if(isBetter != 0){
                        val locOfReplaced = seqCur.indexOf(replaced)
                        val distance = locOfReplaced - seqPre.indexOf(replace)

                        var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                            if(index <= locOfReplaced) restResourceCalls.actions.size
                            else 0
                        }.sum()

                        ( (locOfReplaced + 1) until seqCur.size ).forEach { indexOfCalls ->
                            var isAnyChange = false
                            var changeDegree = 0
                            seqCur[indexOfCalls].actions.forEach {curAction->
                                val actionA = actionIndex - distance

                                val compareResult = replaced.actions.plus(replace.actions).find { it.getName() == curAction.getName() }.run {
                                    if(this == null) ComparisionUtil.compare(actionIndex, current, actionA, previous)
                                    else ComparisionUtil.compare(this.getName(), current, previous)
                                }.also { r-> changeDegree += r }

                                isAnyChange = isAnyChange || compareResult!=0
                                actionIndex += 1

                                //isAnyChange = isAnyChange || compare(actionA, current, actionIndex, previous).also { r-> changeDegree += r } !=0
                            }

                            val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                            if(isAnyChange){

                                val relyOn = if(changeDegree > 0){
                                    mutableListOf(replaced.resourceInstance.getAResourceKey())
                                }else if(changeDegree < 0){
                                    mutableListOf(replace.resourceInstance.getAResourceKey())
                                }else
                                    mutableListOf(replaced.resourceInstance.getAResourceKey(), replace.resourceInstance.getAResourceKey())

                                updateDependencies(seqKey, relyOn, ResourceRestStructureMutator.MutationType.REPLACE.toString())
                            }else{
                                nondependencies.getOrPut(seqKey){ mutableSetOf()}.apply {
                                    add(replaced.resourceInstance.getAResourceKey())
                                    add(replace.resourceInstance.getAResourceKey())
                                }
                            }
                        }

                    }else{
                        /*
                        For instance, ABCDEFG, if we replace B with H become AHCDEFG, then check CDEFG.
                        if there is no any impact on fitness, it probably means {C, D, E, F, G} does not rely on B and H
                        */
                        ((mutatedIndex + 1) until seqCur.size).forEach {
                            val non = seqCur[it].resourceInstance.getAResourceKey()
                            nondependencies.getOrPut(non){ mutableSetOf()}.apply {
                                add(replaced.resourceInstance.getAResourceKey())
                                add(replace.resourceInstance.getAResourceKey())
                            }
                        }
                    }
                }
            }
            1 ->{
                //ADD
                /*
                     For instance, ABCDEFG, if we add H at 3nd position, become ABHCDEFG, then check CDEFG.
                     if C is better, C rely on H; else if C is worse, C rely on H ? ;else C may not rely on H
                */
                val added = seqCur.find { cur -> seqPre.find { pre-> pre.resourceInstance.getKey() == cur.resourceInstance.getKey() } == null }?: return
                val addedKey = added!!.resourceInstance.getAResourceKey()

                val locOfAdded = seqCur.indexOf(added!!)

                if(isBetter != 0){
                    var actionIndex = seqCur.mapIndexed { index, restResourceCalls ->
                        if(index <= locOfAdded) restResourceCalls.actions.size
                        else 0
                    }.sum()

                    val distance = added!!.actions.size

                    (locOfAdded+1 until seqCur.size).forEach { indexOfCalls ->
                        var isAnyChange = false

                        seqCur[indexOfCalls].actions.forEach { curAction->
                            var actionA = actionIndex - distance
                            val compareResult = added.actions.find { it.getName() == curAction.getName() }.run {
                                if(this == null) ComparisionUtil.compare(actionIndex, current, actionA, previous)
                                else ComparisionUtil.compare(this.getName(), current, previous)
                            }

                            isAnyChange = isAnyChange || compareResult!=0
                            actionIndex += 1 //actionB
                        }
                        val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                        if(isAnyChange){
                            updateDependencies(seqKey, mutableListOf(addedKey), ResourceRestStructureMutator.MutationType.ADD.toString())
                        }else{
                            nondependencies.getOrPut(seqKey){ mutableSetOf()}.add(addedKey)
                        }
                    }

                }else{
                    /*
                    For instance, ABCDEFG, if we add H at 3nd position, become ABHCDEFG.
                    if there is no any impact on fitness, it probably means {C, D, E, F, G} does not rely on H
                     */
                    (locOfAdded + 1 until seqCur.size).forEach {
                        val non = seqCur[it].resourceInstance.getAResourceKey()
                        nondependencies.getOrPut(non){ mutableSetOf()}.add(addedKey)
                    }
                }
            }
            -1 ->{
                //DELETE
                /*
                     For instance, ABCDEFG, if B is deleted, become ACDEFG, then check CDEFG.
                     if C is worse, C rely on B;
                        else if C is better, C rely one B ?;
                        else C may not rely on B.

                     there is another case regarding duplicated resources calls (i.e., same resource and same actions) in a test, for instance, ABCB* (B* denotes the 2nd B), if B is deleted, become ACB*, then check CB* as before,
                     when comparing B*, B* probability achieves better performance by taking target from previous first B, so we need to compare with merged targets, i.e., B and B*.
                */
                val delete = seqPre.find { pre -> seqCur.find { cur-> pre.resourceInstance.getKey() == cur.resourceInstance.getKey() } == null }?:return
                val deleteKey = delete!!.resourceInstance.getAResourceKey()

                val locOfDelete = seqPre.indexOf(delete!!)

                if(isBetter != 0){

                    var actionIndex = seqPre.mapIndexed { index, restResourceCalls ->
                        if(index < locOfDelete) restResourceCalls.actions.size
                        else 0
                    }.sum()

                    val distance = 0 - delete!!.actions.size

                    (locOfDelete until seqCur.size).forEach { indexOfCalls ->
                        var isAnyChange = false

                        seqCur[indexOfCalls].actions.forEach { curAction ->
                            val actionA = actionIndex - distance

                            val compareResult = delete.actions.find { it.getName() == curAction.getName() }.run {
                                if(this == null) ComparisionUtil.compare(actionIndex, current, actionA, previous)
                                else ComparisionUtil.compare(this.getName(), current, previous)
                            }

                            isAnyChange = isAnyChange || compareResult!=0
                            actionIndex += 1 //actionB
                        }

                        val seqKey = seqCur[indexOfCalls].resourceInstance.getAResourceKey()
                        if(isAnyChange){
                            updateDependencies(seqKey, mutableListOf(deleteKey), ResourceRestStructureMutator.MutationType.DELETE.toString())
                        }else{
                            nondependencies.getOrPut(seqKey){ mutableSetOf()}.add(deleteKey)
                        }
                    }
                }else{
                    /*
                      For instance, ABCDEFG, if B is deleted, become ACDEFG, then check CDEFG.
                      if there is no impact on fitness, it probably means {C, D, E, F, G} does not rely on B
                     */
                    (locOfDelete until seqCur.size).forEach {
                        val non = seqCur[it].resourceInstance.getAResourceKey()
                        nondependencies.getOrPut(non){ mutableSetOf()}.add(deleteKey)
                    }

                }
            }
            else ->{
                throw IllegalArgumentException("apply undefined structure mutator that changed the size of resources from ${seqPre.size} to ${seqCur.size}")
            }
        }

    }

    /**
     * update dependencies based on derived info
     * [additionalInfo] is structure mutator in this context
     */
    private fun updateDependencies(key : String, target : MutableList<String>, additionalInfo : String, probability : Double = 1.0){

        val relation = if(target.size == 1 && target[0] == key) SelfResourcesRelation(key, probability, additionalInfo)
                    else ResourceRelatedToResources(listOf(key), target, probability, info = additionalInfo)

        updateDependencies(relation, additionalInfo)
    }

    private fun updateDependencies(relation : ResourceRelatedToResources, additionalInfo: String){
        val found = dependencies.getOrPut(relation.originalKey()){ mutableListOf()}.find { it.targets.containsAll(relation.targets) }
        if (found == null) dependencies[relation.originalKey()]!!.add(relation)
        else {
            /*
                TODO Man a strategy to manipulate the probability
             */
            found.probability = max(found.probability,relation.probability)
            if(found.additionalInfo.isBlank())
                found.additionalInfo = additionalInfo
            else if(!found.additionalInfo.contains(additionalInfo))
                found.additionalInfo += ";$additionalInfo"
        }
    }

    private fun deriveTextWithTable(text : String, tables : Set<String> , map : MutableMap<String, MutableList<MatchedInfo>>, inputlevel: Int){
        if(hasDBHandler()){
            tables.forEach { tableName ->
                deriveTextWithTable(text, tableName, map, inputlevel)
            }
        }
    }

    private fun deriveTextWithTable(text : String, tableName: String, map : MutableMap<String, MutableList<MatchedInfo>>, inputlevel : Int): Boolean{
        val score = ParserUtil.stringSimilarityScore(text.toLowerCase(), DbUtil.formatTableName(tableName))
        if(score >= ParserUtil.SimilarityThreshold){
            map.getOrPut(DbUtil.formatTableName(tableName)){ mutableListOf()}.let {
                if(it.none {e->e.matched.toLowerCase() == DbUtil.formatTableName(tableName) }){
                    it.add(MatchedInfo(DbUtil.formatTableName(tableName), score, inputIndicator = inputlevel, outputIndicator = 0))
                }
            }
            if(score == 1.0)  return true
        }

        val fields = (sampler as ResourceRestSampler).sqlInsertBuilder?.getTableInfo(tableName)?.columns
        fields?.let {
            it.forEach {c->
                var scoref = ParserUtil.stringSimilarityScore(text, c.name)
                if(scoref < ParserUtil.SimilarityThreshold)
                    scoref = ParserUtil.stringSimilarityScore(text, tableName+c.name)

                if(scoref >= ParserUtil.SimilarityThreshold){
                    val matched = map.getOrPut(DbUtil.formatTableName(tableName)){ mutableListOf()}
                    if(matched.none {e->e.matched.toLowerCase() == c.name.toLowerCase() }){
                        matched.add(MatchedInfo(c.name.toLowerCase(), scoref, inputIndicator = inputlevel, outputIndicator = 1))
                    }
                }
                if (scoref == 1.0) return true

            }
        }

        return false
    }

    /**
     * this function is used to initialized ad-hoc individuals
     */
    fun createAdHocIndividuals(auth: AuthenticationInfo, adHocInitialIndividuals : MutableList<ResourceRestIndividual>){
        val sortedResources = resourceCluster.values.sortedByDescending { it.path.levels() }.asSequence()

        //GET, PATCH, DELETE
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb != HttpVerb.POST && it.verb != HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach {a->
                    if(a is RestCallAction) a.auth = auth
                }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        //all POST with one post action
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb == HttpVerb.POST}.forEach { a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness, config.maxTestSize)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        sortedResources
                .filter { it.actions.find { a -> a is RestCallAction && a.verb == HttpVerb.POST } != null && it.postCreation.actions.size > 1   }
                .forEach { ar->
                    ar.genPostChain(randomness, config.maxTestSize)?.let {call->
                        call.actions.forEach { (it as RestCallAction).auth = auth }
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
                    }
                }

        //PUT
        sortedResources.forEach { ar->
            ar.actions.filter { it is RestCallAction && it.verb == HttpVerb.PUT }.forEach {a->
                val call = ar.sampleOneAction(a.copy() as RestAction, randomness)
                call.actions.forEach { (it as RestCallAction).auth = auth }
                adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
            }
        }

        //template
        sortedResources.forEach { ar->
            ar.templates.values.filter { t-> t.template.contains(RTemplateHandler.SeparatorTemplate) }
                    .forEach {ct->
                        val call = ar.sampleRestResourceCalls(ct.template, randomness, config.maxTestSize)
                        call.actions.forEach { if(it is RestCallAction) it.auth = auth }
                        adHocInitialIndividuals.add(ResourceRestIndividual(mutableListOf(call), SampleType.SMART_RESOURCE_WITHOUT_DEP))
                    }
        }

    }

    fun isDependencyNotEmpty() : Boolean{
        return dependencies.isNotEmpty()
    }

    fun handleAddDepResource(ind : ResourceRestIndividual, maxTestSize : Int, candidates : MutableList<ResourceRestCalls> = mutableListOf()) : Pair<ResourceRestCalls?, ResourceRestCalls>?{
        //return handleAddDepResource(ind.getResourceCalls().subList(afterPosition+1, ind.getResourceCalls().size).toMutableList(), maxTestSize)
        val options = mutableListOf(0, 1)
        while (options.isNotEmpty()){
            val option = randomness.choose(options)
            val pair = when(option){
                0 -> handleAddNewDepResource(if (candidates.isEmpty()) ind.getResourceCalls().toMutableList() else candidates, maxTestSize)
                1 -> handleAddNotCheckedDepResource(ind, maxTestSize)
                else -> null
            }
            if(pair != null) return pair
            options.remove(option)
        }
        return null
    }

    /**
     * @return pair, first is an existing resource call in [sequence], and second is a newly created resource call that is related to the first
     */
    private fun handleAddNewDepResource(sequence: MutableList<ResourceRestCalls>, maxTestSize : Int) : Pair<ResourceRestCalls?, ResourceRestCalls>?{

        val existingRs = sequence.map { it.resourceInstance.getAResourceKey() }

        val candidates = sequence
                .filter {
                    dependencies.get(it.resourceInstance.getAResourceKey()) != null &&
                            dependencies[it.resourceInstance.getAResourceKey()]!!.any {  dep ->
                                dep.targets.any { t -> existingRs.none {  e -> e == t }  } ||
                                        (dep is SelfResourcesRelation && existingRs.count { e -> e == it.resourceInstance.getAResourceKey() } == 1)
                            }
                }

        if(candidates.isNotEmpty()){
            val first = randomness.choose(candidates)
            /*
                add self relation with a relative low probability, i.e., 20%
             */
            dependencies[first.resourceInstance.getAResourceKey()]!!.flatMap {
                dep-> if(dep !is SelfResourcesRelation) dep.targets.filter {  !existingRs.contains(it) } else if(randomness.nextBoolean(0.2)) dep.targets else mutableListOf()
            }.let { templates->
                if(templates.isNotEmpty()){
                    resourceCluster[randomness.choose(templates)]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )?.let {second->
                        return Pair(first, second)
                    }
                }
            }
        }
        return null
    }

    private fun handleAddNotCheckedDepResource(ind: ResourceRestIndividual, maxTestSize : Int) : Pair<ResourceRestCalls?, ResourceRestCalls>?{
        val checked = ind.getResourceCalls().flatMap {cur->
            findDependentResources(ind, cur).plus(findNonDependentResources(ind, cur))
        }.map { it.resourceInstance.getAResourceKey() }.toHashSet()

        resourceCluster.keys.filter { !checked.contains(it) }.let { templates->
            if(templates.isNotEmpty()){
                resourceCluster[randomness.choose(templates)]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )?.let {second->
                    return Pair(null, second)
                }
            }
        }
        return null
    }

    fun handleDelNonDepResource(ind: ResourceRestIndividual) : ResourceRestCalls?{
        val candidates = ind.getResourceCalls().filter {cur->
            !existsDependentResources(ind, cur) && cur.isDeletable
        }
        if (candidates.isEmpty()) return null

        candidates.filter { isNonDepResources(ind, it) }.apply {
            if(isNotEmpty())
                return randomness.choose(this)
            else
                return randomness.choose(candidates)
        }
    }


    fun handleSwapDepResource(ind: ResourceRestIndividual): Pair<Int, Int>?{
        val options = mutableListOf(1,2,3)
        while (options.isNotEmpty()){
            val option = randomness.choose(options)
            val pair = when(option){
                1 -> adjustDepResource(ind)
                2 -> swapNotConfirmedDepResource(ind)
                3 -> swapNotCheckedResource(ind)
                else -> null
            }
            if(pair != null) return pair
            options.remove(option)
        }
        return null
    }

    private fun adjustDepResource(ind: ResourceRestIndividual): Pair<Int, Int>?{
        val candidates = mutableMapOf<Int, MutableSet<Int>>()
        ind.getResourceCalls().forEachIndexed { index, cur ->
            findDependentResources(ind, cur, minProbability = ParserUtil.SimilarityThreshold).map { ind.getResourceCalls().indexOf(it) }.filter { second -> index < second }.apply {
                if(isNotEmpty()) candidates.getOrPut(index){ mutableSetOf()}.addAll(this.toHashSet())
            }
        }
        if(candidates.isNotEmpty()) randomness.choose(candidates.keys).let {
            return Pair(it, randomness.choose(candidates.getValue(it)))
        }
        return null
    }

    private fun swapNotConfirmedDepResource(ind: ResourceRestIndividual): Pair<Int, Int>?{
        val probCandidates = ind.getResourceCalls().filter { existsDependentResources(ind, it, maxProbability = ParserUtil.SimilarityThreshold) }
        if (probCandidates.isEmpty()) return null
        val first = randomness.choose(probCandidates)
        val second = randomness.choose(findDependentResources(ind, first, maxProbability = ParserUtil.SimilarityThreshold))
        return Pair(ind.getResourceCalls().indexOf(first), ind.getResourceCalls().indexOf(second))
    }

    private fun swapNotCheckedResource(ind: ResourceRestIndividual) : Pair<Int, Int>?{
        val candidates = mutableMapOf<Int, MutableSet<Int>>()
        ind.getResourceCalls().forEachIndexed { index, cur ->
            val checked = findDependentResources(ind, cur).plus(findNonDependentResources(ind, cur))
            ind.getResourceCalls().filter { it != cur && !checked.contains(it) }.map { ind.getResourceCalls().indexOf(it) }.apply {
                if(isNotEmpty()) candidates.getOrPut(index){ mutableSetOf()}.addAll(this)
            }
        }
        if(candidates.isNotEmpty()) randomness.choose(candidates.keys).let {
            return Pair(it, randomness.choose(candidates.getValue(it)))
        }
        return null
    }

    private fun findDependentResources(ind: ResourceRestIndividual, call : ResourceRestCalls, minProbability : Double = 0.0, maxProbability : Double = 1.0): MutableList<ResourceRestCalls>{
        return ind.getResourceCalls().filter {other ->
            (other != call) && dependencies[call.resourceInstance.getAResourceKey()]?.find { r->r.targets.contains(other.resourceInstance.getAResourceKey()) && r.probability >= minProbability&& r.probability <= maxProbability} !=null
        }.toMutableList()
    }

    private fun findNonDependentResources(ind: ResourceRestIndividual, call : ResourceRestCalls): MutableList<ResourceRestCalls>{
        return ind.getResourceCalls().filter { other ->
            (other != call) && nondependencies[call.resourceInstance.getAResourceKey()]?.contains(other.resourceInstance.getAResourceKey())?:false
        }.toMutableList()
    }

    private fun existsDependentResources(ind: ResourceRestIndividual, call : ResourceRestCalls, minProbability : Double = 0.0, maxProbability : Double = 1.0): Boolean{
        return ind.getResourceCalls().find {other ->
            (other != call) && dependencies[call.resourceInstance.getAResourceKey()]?.find { r->r.targets.contains(other.resourceInstance.getAResourceKey()) && r.probability >= minProbability && r.probability <= maxProbability} !=null
        }!=null
    }

    private fun isNonDepResources(ind: ResourceRestIndividual, call : ResourceRestCalls): Boolean{
        return ind.getResourceCalls().find {other ->
            (other != call) && nondependencies[other.resourceInstance.getAResourceKey()]?.contains(call.resourceInstance.getAResourceKey())?:false
        }!=null
    }



    fun handleAddResource(ind : ResourceRestIndividual, maxTestSize : Int) : ResourceRestCalls{
        val existingRs = ind.getResourceCalls().map { it.resourceInstance.getAResourceKey() }
        var candidate = randomness.choose(getResourceCluster().filterNot { r-> existingRs.contains(r.key) }.keys)
        return resourceCluster[candidate]!!.sampleAnyRestResourceCalls(randomness,maxTestSize )
    }

    /**
     *  if involved db, there may a problem to solve,
     *  e.g., an individual "ABCDE",
     *  "B" and "C" are mutual, which means that they are related to same table, "B" -> Tables TAB1, TAB2, and "C" -> Tables TAB2, TAB3
     *  in order to create resources for "B", we insert an row in TAB1 and an row in TAB2, but TAB1 and TAB2 may refer to other tables, so we also need to insert relative
     *  rows in reference tables,
     *  1. if TAB1 and TAB2 do not share any same reference tables, it is simple, just insert row with random values
     *  2. if TAB1 and TAB2 share same reference tables, we may need to remove duplicated insertions
     */
    fun sampleRelatedResources(calls : MutableList<ResourceRestCalls>, sizeOfResource : Int, maxSize : Int) {
        var start = - calls.sumBy { it.actions.size }

        /*
            TODO strategy to select a dependency
         */
        val first = randomness.choose(dependencies.keys)
        sampleCall(first, true, calls, maxSize)
        var sampleSize = 1
        var size = calls.sumBy { it.actions.size } + start
        val excluded = mutableListOf<String>()
        val relatedResources = mutableListOf<ResourceRestCalls>()
        excluded.add(first)
        relatedResources.add(calls.last())

        while (sampleSize < sizeOfResource && size < maxSize){
            val candidates = dependencies[first]!!.flatMap { it.targets as MutableList<String> }.filter { !excluded.contains(it) }
            if(candidates.isEmpty())
                break

            val related = randomness.choose(candidates)
            excluded.add(related)
            sampleCall(related, true, calls, size, false, if(related.isEmpty()) null else relatedResources)
            relatedResources.add(calls.last())
            size = calls.sumBy { it.actions.size } + start
        }
    }

    fun sampleCall(
            resourceKey: String,
            doesCreateResource: Boolean,
            calls : MutableList<ResourceRestCalls>,
            size : Int,
            forceInsert: Boolean = false,
            bindWith : MutableList<ResourceRestCalls>? = null
    ){
        val ar = resourceCluster[resourceKey]
                ?: throw IllegalArgumentException("resource path $resourceKey does not exist!")

        if(!doesCreateResource ){
            val call = ar.sampleIndResourceCall(randomness,size)
            calls.add(call)
            /*
                with a 50% probability, sample GET with an existing resource in db
             */
            if(hasDBHandler() && call.template.template == HttpVerb.GET.toString() && randomness.nextBoolean(0.5)){
                //val created = handleCallWithDBAction(ar, call, false, true)
                generateDbActionForCall(call, forceInsert = false, forceSelect = true)
            }
            return
        }

        assert(!ar.isIndependent())
        var candidateForInsertion : String? = null

        if(hasDBHandler() && ar.paramsToTables.isNotEmpty() && (if(forceInsert) forceInsert else randomness.nextBoolean(0.5))){
            //Insert - GET/PUT/PATCH
            val candidates = ar.templates.filter { it.value.independent }
            candidateForInsertion = if(candidates.isNotEmpty()) randomness.choose(candidates.keys) else null
        }


        val candidate = if(candidateForInsertion.isNullOrBlank()) {
            //prior to select the template with POST
            ar.templates.filter { !it.value.independent }.run {
                if(isNotEmpty())
                    randomness.choose(this.keys)
                else
                    randomness.choose(ar.templates.keys)
            }
        } else candidateForInsertion

        val call = ar.genCalls(candidate,randomness,size,true,true)
        calls.add(call)

        if(hasDBHandler()){
            if(call.status != ResourceRestCalls.ResourceStatus.CREATED
                    || existRelatedTable(call)
                    || candidateForInsertion != null){

                /*
                    derive possible db, and bind value according to db
                */
                //val created = handleCallWithDBAction(ar, call, forceInsert, false)
                val created = generateDbActionForCall(call, forceInsert, forceSelect = false)
                if(!created){
                    //TODO MAN record the call when postCreation fails
                }
            }
        }

        if(bindWith != null){
            bindCallWithFront(call, bindWith)
        }
    }

    fun bindCallWithFront(call: ResourceRestCalls, front : MutableList<ResourceRestCalls>){

        val targets = front.flatMap { it.actions.filter {a -> a is RestCallAction }}

        /*
        TODO

         e.g., A/{a}, A/{a}/B/{b}, A/{a}/C/{c}
         if there are A/{a} and A/{a}/B/{b} that exists in the test,
         1) when appending A/{a}/C/{c}, A/{a} should not be created again;
         2) But when appending A/{a} in the test, A/{a} with new values should be created.
        */
//        if(call.actions.size > 1){
//            call.actions.removeIf {action->
//                action is RestCallAction &&
//                        //(action.verb == HttpVerb.POST || action.verb == HttpVerb.PUT) &&
//                        action.verb == HttpVerb.POST &&
//                        action != call.actions.last() &&
//                        targets.find {it is RestCallAction && it.getName() == action.getName()}.also {
//                            it?.let {ra->
//                                front.find { call-> call.actions.contains(ra) }?.let { call -> call.isStructureMutable = false }
//                                if(action.saveLocation) (ra as RestCallAction).saveLocation = true
//                                action.locationId?.let {
//                                    (ra as RestCallAction).saveLocation = action.saveLocation
//                                }
//                            }
//                        }!=null
//            }
//        }

        /*
         bind values based front actions,
         */
        call.actions
                .filter { it is RestCallAction }
                .forEach { a ->
                    (a as RestCallAction).parameters.forEach { p->
                        targets.forEach { ta->
                            ParamUtil.bindParam(p, a.path, (ta as RestCallAction).path, ta.parameters)
                        }
                    }
                }

        /*
         bind values of dbactions based front dbactions
         */
        front.flatMap { it.dbActions }.apply {
            if(isNotEmpty())
                bindCallWithOtherDBAction(call, this.toMutableList())
        }

        val frontTables = front.map { Pair(it, it.dbActions.map { it.table.name })}.toMap()
        call.dbActions.forEach { db->
           db.table.foreignKeys.map { it.targetTable }.let {ftables->
               frontTables.filter { entry ->
                   entry.value.intersect(ftables).isNotEmpty()
                }.forEach { t, u ->
                   t.isDeletable = false
                   t.shouldBefore.add(call.resourceInstance.getAResourceKey())
               }
           }
        }
    }

    private fun existRelatedTable(call: ResourceRestCalls) : Boolean{
        if(!call.template.independent) return false
        call.actions.filter { it is RestCallAction }.find { !getRelatedTablesByAction(it as RestCallAction).isNullOrEmpty() }.apply {
            if(this != null) return true
        }
        call.actions.filter { it is RestCallAction }.find { resourceCluster[(it as RestCallAction).path.toString()]?.paramsToTables?.isNotEmpty()?:false}.apply {
            if(this != null) return true
        }
        return false
    }


    /**
     * update [resourceTables] based on test results from SUT/EM
     */
    fun updateResourceTables(resourceRestIndividual: ResourceRestIndividual, dto : TestResultsDto){

        val updateMap = mutableMapOf<String, MutableSet<String>>()

        resourceRestIndividual.seeActions().forEachIndexed { index, action ->
            // size of extraHeuristics might be less than size of action due to failure of handling rest action
            if(index < dto.extraHeuristics.size){
                val dbDto = dto.extraHeuristics[index].databaseExecutionDto

                if(action is RestCallAction){
                    val resourceId = action.path.toString()
                    val verb = action.verb.toString()

                    val update = resourceCluster[resourceId]!!.updateActionRelatedToTable(verb, dbDto, dataInDB.keys)
                    val curRelated = resourceCluster[resourceId]!!.getConfirmedRelatedTables()
                    if(update || curRelated.isNotEmpty()){
                        updateParamTable(resourceCluster[resourceId]!!, action)
                        resourceTables.getOrPut(resourceId){ mutableSetOf()}.apply {
                            if(isEmpty() || !containsAll(curRelated)){
                                val newRelated = curRelated.filter { nr -> !this.contains(nr) }
                                updateMap.getOrPut(resourceId){ mutableSetOf()}.addAll(newRelated)
                                this.addAll(curRelated)
                            }
                        }
                    }
                }
            }
        }

        if(updateMap.isNotEmpty()){
            updateDependencyOnceResourceTableUpdate(updateMap)
        }

    }

    private fun updateParamTable(ar: RestResource, action: RestCallAction){
        val ps = ar.paramsToTables.filter { action.parameters.any { p -> ParamRelatedToTable.getNotateKey(p.name) == it.key}}
        val involvedTables = ps.values.flatMap { it.derivedMap.keys }.toHashSet()
        val current = ar.actionToTables.values.flatMap { it.flatMap {  t-> t.tableWithFields.keys } }.toHashSet()
        val removal = involvedTables.filter { !current.contains(it) }
        val new = current.filter { !involvedTables.contains(it) }

        ps.values.forEach {p->
            p.derivedMap.forEach { t, u ->
                if(removal.contains(t)){
                    //decrease the similarity
                    u.forEach { it.modifySimilarity(0.9) }
                }
                if(current.contains(t)){
                    p.probability = 1.0
                    u.forEach { it.setMax() }
                    p.confirmedSet.add(t)
                }
                new.forEach { ntable->
                    val map = mutableMapOf<String, MutableList<MatchedInfo>>()
                    deriveTextWithTable(p.originalKey(), ntable, map, inputlevel = 0)
                    if (map.isNotEmpty()){
                        p.derivedMap.putAll(map)
                        p.confirmedSet.add(ntable)
                    }
                }
            }
        }
    }

    private fun updateDependencyOnceResourceTableUpdate(updateMap: MutableMap<String, MutableSet<String>>){

        updateMap.forEach { resource, tables ->
            val intersectTables : MutableSet<String> = mutableSetOf()
            val relatedResource = resourceTables.filter { it.key != resource }.filter { it.value.intersect(tables).also { intersect -> intersectTables.addAll(intersect) }.isNotEmpty() }

            if(relatedResource.isNotEmpty() && intersectTables.isNotEmpty()){

                val mutualRelations = dependencies
                        .getOrPut(resource){mutableListOf()}
                        .filter { it is MutualResourcesRelations && (it.targets as MutableList<String>).containsAll(relatedResource.keys)}

                if(mutualRelations.isNotEmpty()){
                    //only update confirmed map
                    mutualRelations.forEach { mu -> (mu as MutualResourcesRelations).confirmedSet.addAll(relatedResource.keys.plus(resource).toHashSet()) }
                }else{
                    val newMutualRelation = MutualResourcesRelations(relatedResource.keys.plus(resource).toList(), 1.0, intersectTables)
                    newMutualRelation.confirmedSet.addAll(relatedResource.keys.plus(resource))

                    //completely remove subsume ones
                    val remove = dependencies
                            .getOrPut(resource){mutableListOf()}
                            .filter { it is MutualResourcesRelations && relatedResource.keys.plus(resource).toHashSet().containsAll(it.targets.toHashSet())}

                    remove.forEach { r ->
                        (r.targets as MutableList<String>).forEach { res ->
                            dependencies[res]?.remove(r)
                        }
                    }

                    relatedResource.keys.plus(resource).forEach { res ->
                        dependencies.getOrPut(res){ mutableListOf()}.add(newMutualRelation)
                    }

                }
            }
        }
    }

    /**
     * @return a set of name of tables
     */
    private fun getRelatedTablesByAction(action: RestCallAction) : Set<String>?{
        return resourceCluster[action.path.toString()]?.getConfirmedRelatedTables(action)
    }

    /**
     * @return a set of name of tables
     */
    private fun getAllDerivedRelatedTablesByAction(action: RestCallAction) : Set<String>?{
        return resourceCluster[action.path.toString()]?.paramsToTables?.values?.flatMap { it.targets as MutableList<String> }?.toSet()
    }

    /**
     * @return a set of name of tables
     */
    private fun getDerivedRelatedTablesByAction(action: RestCallAction) : Set<String>?{
        val all = getAllDerivedRelatedTablesByAction(action)
        val derivedByPath = resourceCluster[action.path.toString()]?.tokenToTable?.flatMap { it.value }?.toSet()
        if(derivedByPath == null || derivedByPath.isEmpty()) return all
        if(all == null  || all.isEmpty()) return derivedByPath

        derivedByPath.intersect(all).let {
            if(it.isNotEmpty()) return it
            else
                return derivedByPath
        }
    }

    /**
     * generate dbaction for call
     */
    private fun generateDbActionForCall(call: ResourceRestCalls, forceInsert: Boolean, forceSelect: Boolean) : Boolean{
        val relatedTables = call.actions.filter { it is RestCallAction }.flatMap { getRelatedTablesByAction(it as RestCallAction)?: mutableSetOf() }.toHashSet()

        //if confirmed tables are empty, add all derived tables
        if(relatedTables.isEmpty()){
            relatedTables.addAll(call.actions.filter { it is RestCallAction }.flatMap { getDerivedRelatedTablesByAction(it as RestCallAction)?: mutableSetOf() })
        }

        /**
         * FIXME how to select related tables for preparing db resources for call
         */
        val dbActions = mutableListOf<DbAction>()

        var failToLinkWithResource = false

        relatedTables.reversed().forEach { tableName->
            if(forceInsert){
                generateInserSql(tableName, dbActions)
            }else if(forceSelect){
                if(getRowInDataInDB(tableName) != null && getRowInDataInDB(tableName)!!.isNotEmpty()) generateSelectSql(tableName, dbActions)
                else failToLinkWithResource = true
            }else{
                if(getRowInDataInDB(tableName)!= null ){
                    val size = getRowInDataInDB(tableName)!!.size
                    when{
                        size < config.minRowOfTable -> generateInserSql(tableName, dbActions).apply {
                            failToLinkWithResource = failToLinkWithResource || !this
                        }
                        else ->{
                            if(randomness.nextBoolean(config.probOfSelectFromDB)){
                                generateSelectSql(tableName, dbActions)
                            }else{
                                generateInserSql(tableName, dbActions).apply {
                                    failToLinkWithResource = failToLinkWithResource || !this
                                }
                            }
                        }
                    }
                }else
                    failToLinkWithResource = true
            }
        }

        if(dbActions.isNotEmpty()){
            (0 until (dbActions.size - 1)).forEach { i ->
                (i+1 until dbActions.size).forEach { j ->
                    dbActions[i].table.foreignKeys.any { f->f.targetTable == dbActions[j].table.name}.let {
                        if(it){
                            val idb = dbActions[i]
                            dbActions[i] = dbActions[j]
                            dbActions[j] = idb
                        }
                    }
                }
            }
            DbActionUtils.randomizeDbActionGenes(dbActions, randomness)
            repairDbActions(dbActions)

            val removedDbAction = mutableListOf<DbAction>()

            dbActions.map { it.table.name }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach {tableName->
                removedDbAction.addAll(dbActions.filter { it.table.name == tableName }.run { this.subList(1, this.size) })
            }

            if(removedDbAction.isNotEmpty()){
                dbActions.removeAll(removedDbAction)

                val previous = mutableListOf<DbAction>()
                dbActions.forEachIndexed { index, dbAction ->
                    if(index != 0 && dbAction.table.foreignKeys.isNotEmpty() && dbAction.table.foreignKeys.find { fk -> removedDbAction.find { it.table.name == fk.targetTable } !=null } != null)
                        DbActionUtils.repairFK(dbAction, previous)
                    previous.add(dbAction)
                }
            }

            bindCallWithDBAction(call, dbActions)

            call.dbActions.addAll(dbActions)
        }
        return relatedTables.isNotEmpty() && !failToLinkWithResource
    }


    /**
     *  repair dbaction of resource call after standard mutation
     *  Since standard mutation does not change structure of a test, the involved tables
     *  should be same with previous.
     */
    fun repairRestResourceCalls(call: ResourceRestCalls) {
        call.repairGenesAfterMutation()

        if(hasDBHandler() && call.dbActions.isNotEmpty()){

            val previous = call.dbActions.map { it.table.name }
            call.dbActions.clear()
            //handleCallWithDBAction(ar, call, true, false)
            generateDbActionForCall(call, forceInsert = true, forceSelect = false)

            if(call.dbActions.size != previous.size){
                //remove additions
                call.dbActions.removeIf {
                    !previous.contains(it.table.name)
                }
            }
        }
    }

    private fun generateSelectSql(tableName : String, dbActions: MutableList<DbAction>, forceDifferent: Boolean = false, withDbAction: DbAction?=null){
        if(dbActions.map { it.table.name }.contains(tableName)) return

        assert(getRowInDataInDB(tableName) != null && getRowInDataInDB(tableName)!!.isNotEmpty())
        assert(!forceDifferent || withDbAction == null)

        val columns = if(forceDifferent && withDbAction!!.representExistingData){
            selectToDataRowDto(withDbAction, tableName)
        }else {
            randomness.choose(getRowInDataInDB(tableName)!!)
        }

        val selectDbAction = (sampler as ResourceRestSampler).sqlInsertBuilder!!.extractExistingByCols(tableName, columns)
        dbActions.add(selectDbAction)
    }

    private fun generateInserSql(tableName : String, dbActions: MutableList<DbAction>) : Boolean{
        val insertDbAction =
                (sampler as ResourceRestSampler).sqlInsertBuilder!!
                        .createSqlInsertionActionWithAllColumn(tableName)

        if(insertDbAction.isEmpty()) return false

//        val pasted = mutableListOf<DbAction>()
//        insertDbAction.reversed().forEach {ndb->
//            val index = dbActions.indexOfFirst { it.table.name == ndb.table.name && !it.representExistingData}
//            if(index == -1) pasted.add(0, ndb)
//            else{
//                if(pasted.isNotEmpty()){
//                    dbActions.addAll(index+1, pasted)
//                    pasted.clear()
//                }
//            }
//        }
//
//        if(pasted.isNotEmpty()){
//            if(pasted.size == insertDbAction.size)
//                dbActions.addAll(pasted)
//            else
//                dbActions.addAll(0, pasted)
//        }
        dbActions.addAll(insertDbAction)
        return true
    }

    private fun bindCallWithDBAction(call: ResourceRestCalls, dbActions: MutableList<DbAction>, bindParamBasedOnDB : Boolean = false, excludePost : Boolean = true){
        call.actions
                .filter { (it is RestCallAction) && (!excludePost || it.verb != HttpVerb.POST) }
                .forEach {
                    val map = getParamTableByAction(it as RestCallAction)
                    map.forEach { p_name, paramToTable ->
                        val candidates = paramToTable.getBestDerived()
                        if(candidates.isNotEmpty()){
                            val tableName = randomness.choose(candidates.keys)
                            val matchedInfo = randomness.choose(candidates.getValue(tableName))
                            val relatedDbAction = dbActions.plus(call.dbActions).first { db -> db.table.name.toLowerCase() == tableName.toLowerCase() }
                            val param = it.parameters.find { p-> p.name == paramToTable.originalKey() }!!
                            ParamUtil.bindParam(relatedDbAction, param, matchedInfo,  existingData = bindParamBasedOnDB || relatedDbAction.representExistingData )
                                    .apply {
                                        if(!this)
                                            println("check!")
                                    }

                        }
                    }
                }
    }

    private fun bindCallWithOtherDBAction(call : ResourceRestCalls, dbActions: MutableList<DbAction>){
        val dbRelatedToTables = dbActions.map { it.table.name }.toMutableList()
        val dbTables = call.dbActions.map { it.table.name }.toMutableList()

        if(dbRelatedToTables.containsAll(dbTables)){
            call.dbActions.clear()
        }else{
            call.dbActions.removeIf { dbRelatedToTables.contains(it.table.name) }
            /*
             TODO Man there may need to add selection in order to ensure the reference pk exists
             */
            //val selections = mutableListOf<DbAction>()
            val previous = mutableListOf<DbAction>()
            call.dbActions.forEach {dbaction->
                if(dbaction.table.foreignKeys.find { dbRelatedToTables.contains(it.targetTable) }!=null){
                    val refers = DbActionUtils.repairFK(dbaction, dbActions.plus(previous).toMutableList())
                    //selections.addAll( (sampler as ResourceRestSampler).sqlInsertBuilder!!.generateSelect(refers) )
                }
                previous.add(dbaction)
            }
            repairDbActions(dbActions.plus(call.dbActions).toMutableList())
            //call.dbActions.addAll(0, selections)
        }

        bindCallWithDBAction(call, dbActions.plus(call.dbActions).toMutableList(), bindParamBasedOnDB = true)

    }

    private fun getParamTableByAction(action : RestCallAction): Map<String, ParamRelatedToTable>{
        val resource = resourceCluster[action.path.toString()]!!
        return resource.paramsToTables.filter {
            action.parameters.any { p-> ParamRelatedToTable.getNotateKey(p.name.toLowerCase()).toLowerCase() == it.key.toLowerCase() } }
    }



    private fun selectToDataRowDto(dbAction : DbAction, tableName : String) : DataRowDto{
        dbAction.seeGenes().forEach { assert((it is SqlPrimaryKeyGene || it is ImmutableDataHolderGene || it is SqlForeignKeyGene)) }
        val set = dbAction.seeGenes().filter { it is SqlPrimaryKeyGene }.map { ((it as SqlPrimaryKeyGene).gene as ImmutableDataHolderGene).value }.toSet()
        return randomness.choose(getRowInDataInDB(tableName)!!.filter { it.columnData.toSet().equals(set) })
    }

    private fun hasDBHandler() : Boolean = sampler is ResourceRestSampler && (sampler as ResourceRestSampler).sqlInsertBuilder!= null && config.doesInvolveDB


    private fun getRowInDataInDB(tableName: String) : MutableList<DataRowDto>?{
        dataInDB[tableName]?.let { return it}
        dataInDB[tableName.toLowerCase()]?.let { return it }
        dataInDB[tableName.toUpperCase()]?.let { return it }
        return null
    }

    /**
     * update existing data in db
     * the existing data can be applied to an sampled individual
     */
    private fun snapshotDB(){
        if(hasDBHandler()){
            (sampler as ResourceRestSampler).sqlInsertBuilder!!.extractExistingPKs(dataInDB)
        }
    }

    fun getResourceCluster() : Map<String, RestResource> {
        return resourceCluster.toMap()
    }
    fun onlyIndependentResource() : Boolean {
        return resourceCluster.values.filter{ r -> !r.isIndependent() }.isEmpty()
    }

    /**
     * copy code
     */
    private fun repairDbActions(dbActions: MutableList<DbAction>){
        /**
         * First repair SQL Genes (i.e. SQL Timestamps)
         */
        GeneUtils.repairGenes(dbActions.flatMap { it.seeGenes() })

        /**
         * Now repair database constraints (primary keys, foreign keys, unique fields, etc.)
         */
        DbActionUtils.repairBrokenDbActionsList(dbActions, randomness)
    }

    private fun deriveRelatedTables(ar: RestResource, param: Param) {

        val map = mutableMapOf<String, MutableList<MatchedInfo>>()

        val tables = ar.tokenToTable.flatMap { it.value }.toSet()
        var tokens = deriveRelatedTables(ar, param, tables, map)

        if(map.isEmpty())
            tokens = deriveRelatedTables(ar, param, dataInDB.keys.filter { !tables.any { t-> t.toLowerCase() == it.toLowerCase() } }.toSet(), map)

        if (map.isNotEmpty()) {
            ar.updateParamTable(param.name, map, tokens)
        }
    }

    private fun deriveRelatedTables(ar: RestResource, param: Param, tables: Set<String>, map: MutableMap<String, MutableList<MatchedInfo>>) : String{
        val paramName = param.name
        var tokens = ""
        if (!ParamUtil.isGeneralName(paramName)){
            deriveTextWithTable(paramName, tables, map, inputlevel = 0)
            if(map.isNotEmpty()) tokens = paramName
        }

        if(map.isEmpty() && param is BodyParam){
            param.gene.run {
                if(this is ObjectGene)
                    this.refType
                else
                    null
            }?.let {
                deriveTextWithTable(it, tables, map, inputlevel = 0)
                if(map.isNotEmpty()) tokens = it
            }
        }
        if (map.isEmpty()) {
            val beforeTokens = if(ar.tokens[paramName]!=null && ar.tokens[paramName]!!.isParameter)
                ar.tokens.filter { it.value.level < ar.tokens[paramName]!!.level }.keys.sortedBy { ar.tokens[it]!!.level }
            else ar.tokens.keys

            var previousTokens = ""

            beforeTokens.reversed().forEachIndexed  stopToken@{index, c ->
                previousTokens = "$c$previousTokens"

                if(ParamUtil.isGeneralName(paramName)){
                    var text = "$previousTokens$paramName"
                    deriveTextWithTable(text,tables, map, inputlevel = index+1)
                    if (map.isNotEmpty()) {
                        tokens = text
                        return@stopToken
                    }
                }else{
                    deriveTextWithTable(previousTokens,tables, map,inputlevel = index+1)
                    if (map.isNotEmpty()) {
                        tokens = previousTokens
                        return@stopToken
                    }
                }

                if(index > 0){
                    deriveTextWithTable(c,tables, map, inputlevel = index+1)
                    if (map.isNotEmpty()) {
                        tokens = c
                        return@stopToken
                    }
                }


            }
        }
        return tokens
    }


    /**
     * derive relationship between parameter and tables
     * e.g., a rest action /A/{a}/B/{b} with parameters c, d
     *
     */
    private fun deriveRelatedTables(ar: RestResource, startWithPostIfHas : Boolean = true){
            val params = mutableSetOf<String>()
            var flag = false
            ar.actions.forEach { a ->
                if(a is RestCallAction){
                    if (!flag){
                        a.parameters.filter { it is PathParam }.forEach { p->
                            deriveRelatedTables(ar, p)
                        }
                        flag = true
                    }
                    a.parameters.filter { it !is PathParam }.forEach { p->
                        if(!params.contains(p.name)){

                            deriveRelatedTables(ar, p)
                            params.add(p.name)
                        }
                    }
                }
            }
    }


    private fun bindCallActionsWithDBAction(ps: List<String>, call: ResourceRestCalls, dbActions : List<DbAction>, bindParamBasedOnDB : Boolean = false){
        ps.forEach { pname->
            val pss = ParamUtil.parseParams(pname)
            call.actions
                    .filter { (it is RestCallAction) && it.parameters.find { it.name.toLowerCase() == pss.last().toLowerCase() } != null }
                    .forEach { action->
                        (action as RestCallAction).parameters.filter { it.name.toLowerCase() == pss.last().toLowerCase() }
                                .forEach {param->
                                    dbActions.forEach { db->
                                        ParamUtil.bindParam(db, param,if(pss.size > 1) pss[pss.size - 2] else "", db.representExistingData || bindParamBasedOnDB )
                                    }
                                }
                    }
        }
    }

    private fun probOfResToTable(resourceKey: String, tableName: String) : Double{
        return resourceCluster[resourceKey]!!.paramsToTables.values.filter { it.targets.contains(tableName) }.map { it.probability}.max()!!
    }

    /**
     * @return resources (a list of resource id) whose parameters related to same table [tableName], and its similarity should be not less than [minSimilarity]
     */
    private fun paramToSameTable(resourceKey: String?, tableName: String, minSimilarity : Double = 0.0) : List<String>{
        return resourceCluster
                .filter { resourceKey == null || it.key == resourceKey }
                .filter {
                    it.value.paramsToTables.values
                            .find { p -> p.targets.contains(tableName) && p.probability >= minSimilarity} != null
                }.keys.toList()
    }

}