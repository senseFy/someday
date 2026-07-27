@file:OptIn(kotlin.time.ExperimentalTime::class)

package saien.someday.sync.causality.v2

class WorkspaceEntityCausalityEngineV2(
    private val materializer: WorkspaceCausalityMaterializerV2,
    private val validator: WorkspaceEntityValidatorV2,
) {
    fun reconcile(
        syncEpochId: String,
        key: WorkspaceEntityKeyV2,
        versions: Collection<WorkspaceEntityVersionV2>,
        knownConflicts: Collection<WorkspaceConflictDescriptorV2> = emptyList(),
    ): WorkspaceReconciliationResultV2 {
        val validation = validateGraph(syncEpochId, key, versions)
        if (validation.errors.isNotEmpty()) {
            return WorkspaceReconciliationResultV2.InvalidGraph(validation.errors)
        }
        val working = validation.versions.associateByTo(linkedMapOf()) { it.versionId }
        val generated = mutableListOf<WorkspaceEntityVersionV2>()
        var terminalConflict: WorkspaceConflictDescriptorV2? = null

        while (true) {
            val graph = WorkspaceGraphIndexV2(working)
            val heads = graph.heads()

            payloadCollision(heads)?.let { return invalid(it) }

            // Deletions normalize before any mixed delete/edit decision. This
            // makes D1,D2,E produce the same one-delete/one-edit conflict on
            // every arrival order.
            val deletionHeads = heads.filter { it.kind == WorkspaceEntityVersionKindV2.DELETION }
            if (deletionHeads.size >= 2) {
                val selected = deletionHeads.sortedBy { it.versionId }.take(MAX_WORKSPACE_ENTITY_PARENTS_V2)
                val deletion = WorkspaceDeletionV2(
                    deletedAt = selected.minOf { checkNotNull(it.deletionPayload).deletedAt },
                )
                addAutomatic(
                    selected = selected,
                    content = null,
                    deletion = deletion,
                    algorithm = DELETION_MERGE_ALGORITHM_V2,
                    working = working,
                    generated = generated,
                )?.let { return invalid(it) }
                continue
            }

            val equivalentGroup = heads
                .groupBy { it.payloadDigest }
                .values
                .filter { it.size >= 2 }
                .map { group -> group.sortedBy { it.versionId } }
                .sortedWith(compareBy({ it.first().payloadDigest }, { group -> group.joinToString("\u0000") { it.versionId } }))
                .firstOrNull()
            if (equivalentGroup != null) {
                val selected = equivalentGroup.take(MAX_WORKSPACE_ENTITY_PARENTS_V2)
                val representative = selected.first()
                addAutomatic(
                    selected = selected,
                    content = representative.contentPayload,
                    deletion = representative.deletionPayload,
                    algorithm = EQUIVALENT_MERGE_ALGORITHM_V2,
                    working = working,
                    generated = generated,
                )?.let { return invalid(it) }
                continue
            }

            if (heads.size == 1) {
                val finalGraph = WorkspaceGraphIndexV2(working)
                val conflicts = deriveConflictStates(knownConflicts, active = null, graph = finalGraph)
                return WorkspaceReconciliationResultV2.Reconciled(
                    WorkspaceReconciliationPlanV2(
                        key = key,
                        generatedVersions = generated,
                        finalHeadVersionIds = listOf(heads.single().versionId),
                        outcome = WorkspaceReconciliationOutcomeV2.Projected(heads.single().versionId),
                        conflictStates = conflicts,
                    ),
                )
            }

            val contentHeads = heads.filter { it.kind == WorkspaceEntityVersionKindV2.CONTENT }
            if (contentHeads.isNotEmpty() && deletionHeads.isNotEmpty()) {
                terminalConflict = conflict(
                    syncEpochId,
                    key,
                    heads,
                    baseVersionId = null,
                    reason = WorkspaceConflictReasonV2.CONCURRENT_DELETE_EDIT,
                )
                break
            }

            val selected = contentHeads.sortedBy { it.versionId }.take(MAX_WORKSPACE_ENTITY_PARENTS_V2)
            val baseCandidates = graph.maximalCommonAncestors(selected.map { it.versionId })
            val base = baseCandidates.singleOrNull()
            if (base == null || base.kind != WorkspaceEntityVersionKindV2.CONTENT) {
                terminalConflict = conflict(
                    syncEpochId,
                    key,
                    heads,
                    baseVersionId = base?.versionId,
                    reason = WorkspaceConflictReasonV2.NO_USABLE_MERGE_BASE,
                )
                break
            }
            val merge = mergeContent(
                entityType = key.entityType,
                base = checkNotNull(base.contentPayload),
                heads = selected.map { checkNotNull(it.contentPayload) },
            )
            if (merge.conflictingFields.isNotEmpty()) {
                terminalConflict = conflict(
                    syncEpochId,
                    key,
                    heads,
                    baseVersionId = base.versionId,
                    reason = WorkspaceConflictReasonV2.FIELD_CONFLICT,
                    fields = merge.conflictingFields,
                )
                break
            }
            addAutomatic(
                selected = selected,
                content = checkNotNull(merge.payload),
                deletion = null,
                algorithm = FIELD_MERGE_ALGORITHM_V2,
                working = working,
                generated = generated,
            )?.let { return invalid(it) }
        }

        val active = checkNotNull(terminalConflict)
        knownConflicts.firstOrNull { it.conflictId == active.conflictId && it != active }?.let {
            return invalid(
                WorkspaceCausalityErrorV2(
                    WorkspaceCausalityErrorCodeV2.INVALID_CONFLICT_DESCRIPTOR,
                    relatedId = it.conflictId,
                    safeMessage = "Stored conflict differs from deterministic graph reconciliation.",
                ),
            )
        }
        val finalGraph = WorkspaceGraphIndexV2(working)
        return WorkspaceReconciliationResultV2.Reconciled(
            WorkspaceReconciliationPlanV2(
                key = key,
                generatedVersions = generated,
                finalHeadVersionIds = finalGraph.heads().map { it.versionId },
                outcome = WorkspaceReconciliationOutcomeV2.Conflict(active),
                conflictStates = deriveConflictStates(knownConflicts, active, finalGraph),
            ),
        )
    }

    fun validateResolutionExpectation(
        activeConflict: WorkspaceConflictDescriptorV2?,
        expectedConflictId: String,
        expectedHeadVersionIds: List<String>,
    ): WorkspaceCausalityErrorV2? {
        val expectedHeads = expectedHeadVersionIds.distinct().sorted()
        return if (activeConflict == null ||
            activeConflict.conflictId != expectedConflictId ||
            activeConflict.headVersionIds != expectedHeads
        ) {
            WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.STALE_CONFLICT,
                relatedId = expectedConflictId,
                safeMessage = "Conflict heads changed; refresh before resolving.",
            )
        } else {
            null
        }
    }

    private fun addAutomatic(
        selected: List<WorkspaceEntityVersionV2>,
        content: WorkspaceEntityContentV2?,
        deletion: WorkspaceDeletionV2?,
        algorithm: String,
        working: MutableMap<String, WorkspaceEntityVersionV2>,
        generated: MutableList<WorkspaceEntityVersionV2>,
    ): WorkspaceCausalityErrorV2? {
        val first = selected.first()
        val draft = AutomaticWorkspaceVersionDraftV2(
            syncEpochId = first.syncEpochId,
            entityType = first.entityType,
            entityId = first.entityId,
            parentVersionIds = selected.map { it.versionId }.sorted(),
            kind = if (content != null) WorkspaceEntityVersionKindV2.CONTENT else WorkspaceEntityVersionKindV2.DELETION,
            contentPayload = content,
            deletionPayload = deletion,
            authoredAt = selected.maxOf { it.authoredAt },
            generation = selected.maxOf { it.generation } + 1,
            mergeAlgorithmVersion = algorithm,
        )
        val version = try {
            materializer.materializeAutomaticVersion(draft)
        } catch (_: IllegalArgumentException) {
            return WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.INVALID_AUTOMATIC_VERSION,
                safeMessage = "Automatic version materialization rejected a valid deterministic draft.",
            )
        }
        val matchesDraft = version.syncEpochId == draft.syncEpochId &&
            version.entityType == draft.entityType &&
            version.entityId == draft.entityId &&
            version.parentVersionIds == draft.parentVersionIds &&
            version.kind == draft.kind &&
            version.contentPayload == draft.contentPayload &&
            version.deletionPayload == draft.deletionPayload &&
            version.provenance == null &&
            version.authorActorId == SYSTEM_AUTO_MERGE_ACTOR_V2 &&
            version.authoredAt == draft.authoredAt &&
            version.generation == draft.generation &&
            version.mergeAlgorithmVersion == draft.mergeAlgorithmVersion
        if (!matchesDraft || validator.validateEnvelope(version).isNotEmpty()) {
            return WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.INVALID_AUTOMATIC_VERSION,
                version.versionId,
                safeMessage = "Automatic version materializer returned a noncanonical envelope.",
            )
        }
        val existing = working[version.versionId]
        if (existing != null) {
            return WorkspaceCausalityErrorV2(
                if (existing == version) WorkspaceCausalityErrorCodeV2.DUPLICATE_VERSION_ID
                else WorkspaceCausalityErrorCodeV2.IMMUTABLE_OBJECT_MISMATCH,
                version.versionId,
                safeMessage = "Automatic version id is already bound to another envelope.",
            )
        }
        working[version.versionId] = version
        generated += version
        return null
    }

    private fun conflict(
        syncEpochId: String,
        key: WorkspaceEntityKeyV2,
        heads: List<WorkspaceEntityVersionV2>,
        baseVersionId: String?,
        reason: WorkspaceConflictReasonV2,
        fields: Set<String> = emptySet(),
    ): WorkspaceConflictDescriptorV2 {
        val headIds = heads.map { it.versionId }.sorted()
        return WorkspaceConflictDescriptorV2(
            conflictId = materializer.conflictId(syncEpochId, key, headIds),
            syncEpochId = syncEpochId,
            entityType = key.entityType,
            entityId = key.entityId,
            headVersionIds = headIds,
            baseVersionId = baseVersionId,
            reason = reason,
            conflictingFields = fields,
        )
    }

    private fun deriveConflictStates(
        known: Collection<WorkspaceConflictDescriptorV2>,
        active: WorkspaceConflictDescriptorV2?,
        graph: WorkspaceGraphIndexV2,
    ): List<WorkspaceConflictStateV2> {
        val all = linkedMapOf<String, WorkspaceConflictDescriptorV2>()
        known.sortedBy { it.conflictId }.forEach { all[it.conflictId] = it }
        active?.let { all[it.conflictId] = it }
        return all.values.map { descriptor ->
            val resolution = graph.versions.values
                .asSequence()
                .filter { candidate ->
                    descriptor.headVersionIds.all { graph.isAncestorOrSelf(it, candidate.versionId) }
                }
                .sortedWith(compareBy({ it.generation }, { it.versionId }))
                .firstOrNull()
            when {
                resolution != null -> WorkspaceConflictStateV2(
                    descriptor,
                    WorkspaceConflictLifecycleV2.RESOLVED,
                    resolvedByVersionId = resolution.versionId,
                )
                descriptor.conflictId == active?.conflictId -> WorkspaceConflictStateV2(
                    descriptor,
                    WorkspaceConflictLifecycleV2.ACTIVE,
                )
                active != null -> WorkspaceConflictStateV2(
                    descriptor,
                    WorkspaceConflictLifecycleV2.SUPERSEDED,
                    supersededByConflictId = active.conflictId,
                )
                else -> WorkspaceConflictStateV2(
                    descriptor,
                    WorkspaceConflictLifecycleV2.SUPERSEDED,
                    // A projected graph without a descendant for a historical
                    // record indicates imported stale metadata. Close it
                    // locally; it cannot become active without this head set.
                    supersededByConflictId = descriptor.conflictId,
                )
            }
        }.sortedBy { it.descriptor.conflictId }
    }

    private fun payloadCollision(versions: Collection<WorkspaceEntityVersionV2>): WorkspaceCausalityErrorV2? =
        versions.groupBy { it.payloadDigest }.values.firstNotNullOfOrNull { group ->
            if (group.size < 2) return@firstNotNullOfOrNull null
            val first = group.first()
            val firstBytes = materializer.canonicalPayloadBytes(first)
            group.drop(1).firstOrNull { !materializer.canonicalPayloadBytes(it).contentEquals(firstBytes) }?.let { other ->
                WorkspaceCausalityErrorV2(
                    WorkspaceCausalityErrorCodeV2.PAYLOAD_DIGEST_COLLISION,
                    first.versionId,
                    other.versionId,
                    "Equal payload digests identify unequal canonical payload bytes.",
                )
            }
        }

    private fun validateGraph(
        syncEpochId: String,
        key: WorkspaceEntityKeyV2,
        input: Collection<WorkspaceEntityVersionV2>,
    ): WorkspaceGraphValidationV2 {
        if (input.isEmpty()) {
            return WorkspaceGraphValidationV2(
                emptyList(),
                listOf(WorkspaceCausalityErrorV2(WorkspaceCausalityErrorCodeV2.EMPTY_GRAPH, safeMessage = "Cannot reconcile an empty entity graph.")),
            )
        }
        val errors = mutableListOf<WorkspaceCausalityErrorV2>()
        val versions = linkedMapOf<String, WorkspaceEntityVersionV2>()
        val copiesByVersionId = input.groupBy { it.versionId }
        copiesByVersionId.keys.sorted().forEach { versionId ->
            val copies = checkNotNull(copiesByVersionId[versionId])
            val first = copies.first()
            when {
                copies.any { it.objectDigest != first.objectDigest } -> errors += WorkspaceCausalityErrorV2(
                    WorkspaceCausalityErrorCodeV2.IMMUTABLE_OBJECT_MISMATCH,
                    versionId,
                    safeMessage = "One version id identifies different object digests.",
                )
                copies.any { it != first } -> errors += WorkspaceCausalityErrorV2(
                    WorkspaceCausalityErrorCodeV2.DUPLICATE_VERSION_ID,
                    versionId,
                    safeMessage = "One version id identifies unequal envelopes with the same claimed digest.",
                )
            }
            versions[versionId] = first
        }
        versions.values.forEach { version ->
            errors += validator.validateEnvelope(version)
            if (version.syncEpochId != syncEpochId) errors += WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.EPOCH_MISMATCH,
                version.versionId,
                version.syncEpochId,
                "Version belongs to another epoch.",
            )
            if (version.key != key) errors += WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.ENTITY_MISMATCH,
                version.versionId,
                version.entityId,
                "Version belongs to another entity.",
            )
        }
        versions.values.forEach { version ->
            version.parentVersionIds.forEach { parentId ->
                val parent = versions[parentId]
                when {
                    parent == null -> errors += WorkspaceCausalityErrorV2(
                        WorkspaceCausalityErrorCodeV2.MISSING_PARENT,
                        version.versionId,
                        parentId,
                        "A same-entity parent is missing.",
                    )
                    parent.syncEpochId != version.syncEpochId || parent.key != version.key -> errors += WorkspaceCausalityErrorV2(
                        WorkspaceCausalityErrorCodeV2.CROSS_ENTITY_PARENT,
                        version.versionId,
                        parentId,
                        "A parent crosses epoch or entity identity.",
                    )
                }
            }
        }
        val graph = WorkspaceGraphIndexV2(versions)
        graph.cyclicVersionIds().forEach { id -> errors += WorkspaceCausalityErrorV2(
            WorkspaceCausalityErrorCodeV2.CYCLIC_GRAPH,
            id,
            safeMessage = "Entity version graph contains a cycle.",
        ) }
        if (errors.none { it.code == WorkspaceCausalityErrorCodeV2.CYCLIC_GRAPH }) {
            versions.values.forEach { version ->
                val parents = version.parentVersionIds.mapNotNull(versions::get)
                if (parents.size == version.parentVersionIds.size) {
                    val expected = parents.maxOfOrNull { it.generation }?.plus(1) ?: 1
                    if (version.generation != expected) errors += WorkspaceCausalityErrorV2(
                        WorkspaceCausalityErrorCodeV2.INVALID_GENERATION,
                        version.versionId,
                        safeMessage = "Version generation does not equal one plus its parent maximum.",
                    )
                }
                errors += transitionErrors(version, parents)
                if (parents.size == version.parentVersionIds.size) {
                    errors += automaticSemanticErrors(version, parents, graph)
                }
            }
        }
        payloadCollision(versions.values)?.let(errors::add)
        return WorkspaceGraphValidationV2(versions.values.toList(), errors.distinct())
    }

    private fun transitionErrors(
        version: WorkspaceEntityVersionV2,
        parents: List<WorkspaceEntityVersionV2>,
    ): List<WorkspaceCausalityErrorV2> {
        val invalid = when {
            version.kind == WorkspaceEntityVersionKindV2.DELETION && parents.isEmpty() ->
                version.provenance?.type !in setOf(
                    WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT,
                    WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT,
                )
            version.kind == WorkspaceEntityVersionKindV2.DELETION &&
                version.mergeAlgorithmVersion == null && parents.singleOrNull()?.kind != WorkspaceEntityVersionKindV2.CONTENT -> true
            version.mergeAlgorithmVersion in AUTOMATIC_MERGE_ALGORITHMS_V2 -> false
            version.mergeAlgorithmVersion == MANUAL_RESOLUTION_ALGORITHM_V2 -> parents.size < 2
            version.provenance?.type == WorkspaceVersionProvenanceTypeV2.SOURCE_IMPORT -> parents.size > 1
            version.provenance?.type == WorkspaceVersionProvenanceTypeV2.EPOCH_CHECKPOINT -> parents.isNotEmpty()
            else -> parents.size > 1
        }
        return if (!invalid) emptyList() else listOf(
            WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.INVALID_TRANSITION,
                version.versionId,
                safeMessage = "Version kind and parent transition are invalid.",
            ),
        )
    }

    /**
     * Automatic versions are protocol results, not assertions trusted from a
     * writer. Recompute the selected algorithm from the complete retained
     * parent graph so a valid HMAC/id cannot bless a semantically invented
     * merge output.
     */
    private fun automaticSemanticErrors(
        version: WorkspaceEntityVersionV2,
        parents: List<WorkspaceEntityVersionV2>,
        graph: WorkspaceGraphIndexV2,
    ): List<WorkspaceCausalityErrorV2> {
        val algorithm = version.mergeAlgorithmVersion
        if (algorithm !in AUTOMATIC_MERGE_ALGORITHMS_V2) return emptyList()

        val concurrentParents = parents.indices.all { left ->
            parents.indices.all { right ->
                left == right || (!graph.isAncestorOrSelf(parents[left].versionId, parents[right].versionId) &&
                    !graph.isAncestorOrSelf(parents[right].versionId, parents[left].versionId))
            }
        }
        val commonShape = parents.size in 2..MAX_WORKSPACE_ENTITY_PARENTS_V2 &&
            concurrentParents &&
            version.authoredAt == parents.maxOf { it.authoredAt } &&
            version.generation == parents.maxOf { it.generation } + 1

        val semanticMatch = commonShape && when (algorithm) {
            EQUIVALENT_MERGE_ALGORITHM_V2 -> {
                parents.all { it.kind == WorkspaceEntityVersionKindV2.CONTENT } &&
                    parents.map { it.payloadDigest }.distinct().size == 1 &&
                    parents.drop(1).all {
                        materializer.canonicalPayloadBytes(it)
                            .contentEquals(materializer.canonicalPayloadBytes(parents.first()))
                    } &&
                    version.kind == WorkspaceEntityVersionKindV2.CONTENT &&
                    version.contentPayload == parents.first().contentPayload &&
                    version.deletionPayload == null
            }
            DELETION_MERGE_ALGORITHM_V2 -> {
                parents.all { it.kind == WorkspaceEntityVersionKindV2.DELETION } &&
                    version.kind == WorkspaceEntityVersionKindV2.DELETION &&
                    version.contentPayload == null &&
                    version.deletionPayload == WorkspaceDeletionV2(
                        parents.minOf { checkNotNull(it.deletionPayload).deletedAt },
                    )
            }
            FIELD_MERGE_ALGORITHM_V2 -> {
                val bases = graph.maximalCommonAncestors(parents.map { it.versionId })
                val base = bases.singleOrNull()
                if (base?.kind != WorkspaceEntityVersionKindV2.CONTENT ||
                    parents.any { it.kind != WorkspaceEntityVersionKindV2.CONTENT }
                ) {
                    false
                } else {
                    val merge = mergeContent(
                        version.entityType,
                        checkNotNull(base.contentPayload),
                        parents.map { checkNotNull(it.contentPayload) },
                    )
                    merge.conflictingFields.isEmpty() &&
                        version.kind == WorkspaceEntityVersionKindV2.CONTENT &&
                        version.contentPayload == merge.payload &&
                        version.deletionPayload == null
                }
            }
            else -> false
        }
        return if (semanticMatch) emptyList() else listOf(
            WorkspaceCausalityErrorV2(
                WorkspaceCausalityErrorCodeV2.INVALID_AUTOMATIC_VERSION,
                version.versionId,
                safeMessage = "Automatic version does not equal the deterministic result of its retained parents.",
            ),
        )
    }

    private fun mergeContent(
        entityType: WorkspaceEntityTypeV2,
        base: WorkspaceEntityContentV2,
        heads: List<WorkspaceEntityContentV2>,
    ): WorkspaceTypedMergeV2 = when (entityType) {
        WorkspaceEntityTypeV2.NOTE -> mergeNote(base as NoteContentV2, heads.map { it as NoteContentV2 })
        WorkspaceEntityTypeV2.NOTEBOOK -> mergeNotebook(base as NotebookContentV2, heads.map { it as NotebookContentV2 })
        WorkspaceEntityTypeV2.WORKSPACE_PREFERENCES -> mergePreferences(
            base as WorkspacePreferencesV2,
            heads.map { it as WorkspacePreferencesV2 },
        )
    }

    private fun mergeNote(base: NoteContentV2, heads: List<NoteContentV2>): WorkspaceTypedMergeV2 {
        val notebook = mergeField("notebookId", base.notebookId, heads.map { it.notebookId })
        val title = mergeField("title", base.title, heads.map { it.title })
        val body = mergeField("markdownBody", base.markdownBody, heads.map { it.markdownBody })
        val created = mergeField("noteCreatedAt", base.noteCreatedAt, heads.map { it.noteCreatedAt })
        val zone = mergeField("timeZoneId", base.timeZoneId, heads.map { it.timeZoneId })
        val location = mergeField("location", base.location, heads.map { it.location })
        val fields = listOf(notebook, title, body, created, zone, location)
            .flatMap { it.conflicts }.distinct().sorted().toSet()
        return WorkspaceTypedMergeV2(
            payload = if (fields.isEmpty()) NoteContentV2(
                notebook.value,
                title.value,
                body.value,
                created.value,
                zone.value,
                location.value,
            ) else null,
            conflictingFields = fields,
        )
    }

    private fun mergeNotebook(base: NotebookContentV2, heads: List<NotebookContentV2>): WorkspaceTypedMergeV2 {
        val title = mergeField("title", base.title, heads.map { it.title })
        val order = mergeField("sortOrder", base.sortOrder, heads.map { it.sortOrder })
        val created = mergeField("notebookCreatedAt", base.notebookCreatedAt, heads.map { it.notebookCreatedAt })
        val fields = listOf(title, order, created).flatMap { it.conflicts }.distinct().sorted().toSet()
        return WorkspaceTypedMergeV2(
            payload = if (fields.isEmpty()) NotebookContentV2(title.value, order.value, created.value) else null,
            conflictingFields = fields,
        )
    }

    private fun mergePreferences(base: WorkspacePreferencesV2, heads: List<WorkspacePreferencesV2>): WorkspaceTypedMergeV2 {
        val theme = mergeField("theme", base.theme, heads.map { it.theme })
        val preview = mergeField("previewByDefault", base.previewByDefault, heads.map { it.previewByDefault })
        val toolbar = mergeField("markdownToolbarVisible", base.markdownToolbarVisible, heads.map { it.markdownToolbarVisible })
        val notebook = mergeField("defaultNotebookId", base.defaultNotebookId, heads.map { it.defaultNotebookId })
        val fields = listOf(theme, preview, toolbar, notebook).flatMap { it.conflicts }.distinct().sorted().toSet()
        return WorkspaceTypedMergeV2(
            payload = if (fields.isEmpty()) WorkspacePreferencesV2(theme.value, preview.value, toolbar.value, notebook.value) else null,
            conflictingFields = fields,
        )
    }

    private fun <T> mergeField(name: String, base: T, heads: List<T>): MergedFieldV2<T> {
        val changed = heads.filter { it != base }.distinct()
        return if (changed.size <= 1) {
            MergedFieldV2(changed.singleOrNull() ?: base, emptySet())
        } else {
            MergedFieldV2(base, setOf(name))
        }
    }

    private fun invalid(error: WorkspaceCausalityErrorV2): WorkspaceReconciliationResultV2.InvalidGraph =
        WorkspaceReconciliationResultV2.InvalidGraph(listOf(error))
}

private data class WorkspaceGraphValidationV2(
    val versions: List<WorkspaceEntityVersionV2>,
    val errors: List<WorkspaceCausalityErrorV2>,
)

private data class WorkspaceTypedMergeV2(
    val payload: WorkspaceEntityContentV2?,
    val conflictingFields: Set<String>,
)

private data class MergedFieldV2<T>(
    val value: T,
    val conflicts: Set<String>,
)

private class WorkspaceGraphIndexV2(
    val versions: Map<String, WorkspaceEntityVersionV2>,
) {
    private val children: Map<String, Set<String>> =
        mutableMapOf<String, MutableSet<String>>().apply {
            versions.keys.forEach { put(it, linkedSetOf()) }
            versions.values.forEach { child ->
                child.parentVersionIds.forEach { parent ->
                    getOrPut(parent) { linkedSetOf() }.add(child.versionId)
                }
            }
        }

    fun heads(): List<WorkspaceEntityVersionV2> = versions.values
        .filter { children[it.versionId].isNullOrEmpty() }
        .sortedBy { it.versionId }

    fun isAncestorOrSelf(ancestor: String, descendant: String): Boolean {
        if (ancestor == descendant) return true
        val pending = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        pending += descendant
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            versions[current]?.parentVersionIds.orEmpty().forEach { parent ->
                if (parent == ancestor) return true
                pending += parent
            }
        }
        return false
    }

    fun maximalCommonAncestors(versionIds: List<String>): List<WorkspaceEntityVersionV2> {
        if (versionIds.isEmpty()) return emptyList()
        val common = versionIds
            .map(::ancestorsIncludingSelf)
            .reduce { acc, values -> acc.intersect(values) }
        return common
            .filter { candidate -> common.none { other -> other != candidate && isAncestorOrSelf(candidate, other) } }
            .mapNotNull(versions::get)
            .sortedBy { it.versionId }
    }

    fun cyclicVersionIds(): List<String> {
        val states = mutableMapOf<String, Int>()
        val cyclic = mutableSetOf<String>()
        fun visit(id: String, stack: MutableList<String>) {
            when (states[id]) {
                1 -> {
                    val start = stack.indexOf(id).coerceAtLeast(0)
                    cyclic += stack.drop(start)
                    return
                }
                2 -> return
            }
            states[id] = 1
            stack += id
            versions[id]?.parentVersionIds.orEmpty().forEach { if (it in versions) visit(it, stack) }
            stack.removeAt(stack.lastIndex)
            states[id] = 2
        }
        versions.keys.sorted().forEach { visit(it, mutableListOf()) }
        return cyclic.sorted()
    }

    private fun ancestorsIncludingSelf(id: String): Set<String> {
        val result = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += id
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!result.add(current)) continue
            versions[current]?.parentVersionIds.orEmpty().forEach { pending += it }
        }
        return result
    }
}
