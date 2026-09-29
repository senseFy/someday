INSERT INTO settings VALUES ('fixture-setting','preserve me',100);
INSERT INTO devices VALUES ('writer','Laptop','desktop',100,101,'opaque-key-metadata');
INSERT INTO media_assets VALUES (
 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
 12,'image/png','picture.png',3,4,100,'available',101,
 'https://sync.example|user','workspace-11111111111111111111111111111111','encrypted-digest');
INSERT INTO sync_epochs_v2 VALUES ('remote','epoch','active','healthy',2,2,'keys',1000,
 'descriptor-json','descriptor-digest','checkpoint','checkpoint-digest',100,101,NULL,NULL,'https://sync.example|user');
INSERT INTO sync_local_authority_system_v2 VALUES (1,'remote','epoch','writer','https://sync.example|user','pointer-digest',101);
INSERT INTO sync_dead_letters_v2 VALUES ('remote','epoch','stream','bad-unit','cursor','digest','object','digest','ciphertext',
 'persistent_integrity','bad_object','Safe error',100,101,NULL,0);
INSERT INTO sync_run_history_v2(run_id,remote_profile,epoch_id,started_at,finished_at,status,pushed_objects)
 VALUES ('run','remote','epoch',100,101,'success',2);
INSERT INTO workspace_entity_versions_v2(epoch_id,version_id,contract_id,schema_set_version,envelope_schema_version,
 entity_type,entity_schema_version,entity_id,kind,canonical_payload,author_actor_id,authored_at_seconds,authored_at_nanos,generation,payload_digest,object_digest)
 VALUES ('epoch','note-v1','someday-system-v2','workspace-entity-schema-set-v2',1,'note',1,'note','content',X'000102ff','writer',100,10,1,'payload-1','object-1');
INSERT INTO workspace_entity_versions_v2(epoch_id,version_id,contract_id,schema_set_version,envelope_schema_version,
 entity_type,entity_schema_version,entity_id,kind,canonical_payload,author_actor_id,authored_at_seconds,authored_at_nanos,generation,payload_digest,object_digest)
 VALUES ('epoch','note-v2','someday-system-v2','workspace-entity-schema-set-v2',1,'note',1,'note','content',X'fffefd00','writer',101,11,2,'payload-2','object-2');
INSERT INTO workspace_entity_versions_v2(epoch_id,version_id,contract_id,schema_set_version,envelope_schema_version,
 entity_type,entity_schema_version,entity_id,kind,canonical_payload,author_actor_id,authored_at_seconds,authored_at_nanos,generation,payload_digest,object_digest)
 VALUES ('epoch','notebook-v1','someday-system-v2','workspace-entity-schema-set-v2',1,'notebook',1,'notebook','content',X'1234','writer',100,10,1,'payload-3','object-3');
INSERT INTO workspace_entity_versions_v2(epoch_id,version_id,contract_id,schema_set_version,envelope_schema_version,
 entity_type,entity_schema_version,entity_id,kind,canonical_payload,author_actor_id,authored_at_seconds,authored_at_nanos,generation,payload_digest,object_digest)
 VALUES ('epoch','preferences-v1','someday-system-v2','workspace-entity-schema-set-v2',1,'workspace_preferences',1,'workspace-preferences','content',X'5678','writer',100,10,1,'payload-4','object-4');
INSERT INTO workspace_entity_version_parents_v2 VALUES ('epoch','note','note','note-v2','note-v1');
INSERT INTO workspace_entity_heads_v2 VALUES ('epoch','note','note','note-v2');
INSERT INTO workspace_entity_conflicts_v2 VALUES ('epoch','conflict','note','note','note-v1','field_conflict','title',102,'active',NULL,NULL);
INSERT INTO workspace_entity_conflict_heads_v2 VALUES ('epoch','conflict','note-v2');
INSERT INTO note_projections_system_v2(epoch_id,note_id,preferred_head_version_id,state,referenced_notebook_id,effective_notebook_id,title,markdown_body,note_created_at_seconds,note_created_at_nanos,time_zone_id)
 VALUES ('epoch','note','note-v2','content','notebook','notebook','Saved note','body with 图片',100,12,'Asia/Shanghai');
INSERT INTO notebook_projections_v2(epoch_id,notebook_id,preferred_head_version_id,state,title,sort_order) VALUES ('epoch','notebook','notebook-v1','content','Saved notebook',4);
INSERT INTO workspace_preferences_projection_v2(epoch_id,preferred_head_version_id,state,theme,preview_by_default,markdown_toolbar_visible,referenced_default_notebook_id,effective_default_notebook_id)
 VALUES ('epoch','preferences-v1','content','dark',1,0,'notebook','notebook');
INSERT INTO projection_warnings_v2 VALUES ('epoch','note','note','conflict',NULL,NULL,100);
INSERT INTO sync_pending_mutations_system_v2 VALUES ('remote','epoch','pending','note-v2','object-2','writer','encrypted-outer',101,102,1);
INSERT INTO sync_applied_mutations_system_v2 VALUES ('remote','epoch','applied','note-v1','object-1',100,'writer');
INSERT INTO sync_remote_cursors_system_v2 VALUES ('remote','epoch','stream','cursor-1','unit-1','unit-digest',100);
INSERT INTO sync_checkpoints_system_v2 VALUES ('remote','epoch','checkpoint','manifest-digest','encrypted-manifest','active',100,101);
INSERT INTO sync_checkpoint_objects_system_v2 VALUES ('remote','epoch','checkpoint',0,0,'note-v1','object-1','encrypted-outer');
INSERT INTO sync_control_objects_system_v2 VALUES ('remote','epoch','sync_epoch_pointer_v2','pointer','pointer-digest','encrypted-pointer','active',100,101);
INSERT INTO sync_source_imports_system_v2 VALUES ('remote','epoch','portable',NULL,NULL,NULL,'old-object','old-digest','note','old-note','note','note-v1','imported','published',100,101);
