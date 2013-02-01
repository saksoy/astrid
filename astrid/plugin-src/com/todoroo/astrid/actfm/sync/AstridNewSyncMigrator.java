package com.todoroo.astrid.actfm.sync;

import android.text.TextUtils;
import android.util.Log;

import com.todoroo.andlib.data.DatabaseDao;
import com.todoroo.andlib.data.Property;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.actfm.sync.messages.NameMaps;
import com.todoroo.astrid.dao.MetadataDao.MetadataCriteria;
import com.todoroo.astrid.dao.OutstandingEntryDao;
import com.todoroo.astrid.dao.TagDataDao;
import com.todoroo.astrid.dao.TagOutstandingDao;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.dao.TaskOutstandingDao;
import com.todoroo.astrid.dao.UpdateDao;
import com.todoroo.astrid.dao.UserActivityDao;
import com.todoroo.astrid.dao.UserDao;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.OutstandingEntry;
import com.todoroo.astrid.data.RemoteModel;
import com.todoroo.astrid.data.SyncFlags;
import com.todoroo.astrid.data.TagData;
import com.todoroo.astrid.data.TagOutstanding;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.data.TaskOutstanding;
import com.todoroo.astrid.data.Update;
import com.todoroo.astrid.data.User;
import com.todoroo.astrid.data.UserActivity;
import com.todoroo.astrid.helper.UUIDHelper;
import com.todoroo.astrid.service.MetadataService;
import com.todoroo.astrid.service.TagDataService;
import com.todoroo.astrid.tags.TaskToTagMetadata;
import com.todoroo.astrid.utility.Constants;

@SuppressWarnings("nls")
public class AstridNewSyncMigrator {

    @Autowired private MetadataService metadataService;
    @Autowired private TagDataService tagDataService;
    @Autowired private TagDataDao tagDataDao;
    @Autowired private TaskDao taskDao;
    @Autowired private UpdateDao updateDao;
    @Autowired private UserActivityDao userActivityDao;
    @Autowired private UserDao userDao;

    @Autowired private TaskOutstandingDao taskOutstandingDao;
    @Autowired private TagOutstandingDao tagOutstandingDao;

    public static final String PREF_SYNC_MIGRATION = "sync_migration";

    private static final String LOG_TAG = "sync-migrate";

    public AstridNewSyncMigrator() {
        DependencyInjectionService.getInstance().inject(this);
    }

    @SuppressWarnings("deprecation")
    public void performMigration() {
        if (Preferences.getBoolean(PREF_SYNC_MIGRATION, false))
            return;

        // --------------
        // First ensure that a TagData object exists for each tag metadata
        // --------------
        Query noTagDataQuery = Query.select(Metadata.PROPERTIES).where(Criterion.and(
                MetadataCriteria.withKey(TaskToTagMetadata.KEY),
                Criterion.or(TaskToTagMetadata.TAG_UUID.isNull(), TaskToTagMetadata.TAG_UUID.eq(0)),
                Criterion.not(TaskToTagMetadata.TAG_NAME.in(Query.select(TagData.NAME).from(TagData.TABLE))))).groupBy(TaskToTagMetadata.TAG_NAME);

        TodorooCursor<Metadata> noTagData = metadataService.query(noTagDataQuery);
        try {
            Metadata tag = new Metadata();
            for (noTagData.moveToFirst(); !noTagData.isAfterLast(); noTagData.moveToNext()) {
                tag.readFromCursor(noTagData);

                if (Constants.DEBUG)
                    Log.w(LOG_TAG, "CREATING TAG DATA " + tag.getValue(TaskToTagMetadata.TAG_NAME));

                TagData newTagData = new TagData();
                newTagData.setValue(TagData.NAME, tag.getValue(TaskToTagMetadata.TAG_NAME));
                tagDataService.save(newTagData);
            }
        } finally {
            noTagData.close();
        }

        // --------------
        // Then ensure that every remote model has a remote id, by generating one using the uuid generator for all those without one
        // --------------
        Query tagsQuery = Query.select(TagData.ID, TagData.UUID).where(Criterion.or(TagData.UUID.eq(RemoteModel.NO_UUID), TagData.UUID.isNull()));
        assertUUIDsExist(tagsQuery, new TagData(), tagDataDao, tagOutstandingDao, new TagOutstanding(), NameMaps.syncableProperties(NameMaps.TABLE_ID_TAGS), null);

        Query tasksQuery = Query.select(Task.ID, Task.UUID, Task.RECURRENCE, Task.FLAGS).where(Criterion.all);
        assertUUIDsExist(tasksQuery, new Task(), taskDao, taskOutstandingDao, new TaskOutstanding(), NameMaps.syncableProperties(NameMaps.TABLE_ID_TASKS), new UUIDAssertionExtras<Task>() {
            @Override
            public void beforeSave(Task instance) {
                if (instance.getFlag(Task.FLAGS, Task.FLAG_IS_READONLY)) {
                    instance.setFlag(Task.FLAGS, Task.FLAG_IS_READONLY, false);
                    instance.setValue(Task.IS_READONLY, 1);
                }

                if (instance.getFlag(Task.FLAGS, Task.FLAG_PUBLIC)) {
                    instance.setFlag(Task.FLAGS, Task.FLAG_PUBLIC, false);
                    instance.setValue(Task.IS_PUBLIC, 1);
                }

                String recurrence = instance.getValue(Task.RECURRENCE);
                if (!TextUtils.isEmpty(recurrence)) {
                    boolean repeatAfterCompletion = instance.getFlag(Task.FLAGS, Task.FLAG_REPEAT_AFTER_COMPLETION);
                    instance.setFlag(Task.FLAGS, Task.FLAG_REPEAT_AFTER_COMPLETION, false);

                    recurrence = recurrence.replaceAll("BYDAY=;", "");
                    if (repeatAfterCompletion)
                        recurrence = recurrence + ";FROM=COMPLETION";
                    instance.setValue(Task.RECURRENCE, recurrence);
                }
            }
        });

        // ----------
        // Migrate unsynced task comments to UserActivity table
        // ----------

        TodorooCursor<Update> updates = updateDao.query(Query.select(Update.PROPERTIES).where(
                Criterion.and(Criterion.or(Update.UUID.eq(0), Update.UUID.isNull()), Criterion.or(Update.ACTION_CODE.eq(UserActivity.ACTION_TAG_COMMENT),
                        Update.ACTION_CODE.eq(UserActivity.ACTION_TASK_COMMENT)))));
        try {
            Update update = new Update();
            UserActivity userActivity = new UserActivity();
            for (updates.moveToFirst(); !updates.isAfterLast(); updates.moveToNext()) {
                update.clear();
                userActivity.clear();

                update.readFromCursor(updates);

                boolean setTarget = true;
                if (!RemoteModel.isUuidEmpty(update.getValue(Update.TASK_UUID))) {
                    userActivity.setValue(UserActivity.TARGET_ID, update.getValue(Update.TASK_UUID));
                } else if (update.getValue(Update.TASK_LOCAL) > 0) {
                    Task local = taskDao.fetch(update.getValue(Update.TASK_LOCAL), Task.UUID);
                    if (local != null && !RemoteModel.isUuidEmpty(local.getUuid()))
                        userActivity.setValue(UserActivity.TARGET_ID, local.getUuid());
                    else
                        setTarget = false;
                } else {
                    setTarget = false;
                }

                if (setTarget) {
                    userActivity.setValue(UserActivity.USER_UUID, update.getValue(Update.USER_ID));
                    userActivity.setValue(UserActivity.ACTION, update.getValue(Update.ACTION_CODE));
                    userActivity.setValue(UserActivity.MESSAGE, update.getValue(Update.MESSAGE));
                    userActivity.setValue(UserActivity.CREATED_AT, update.getValue(Update.CREATION_DATE));
                    userActivityDao.createNew(userActivity);
                }

            }
        } finally {
            updates.close();
        }


        // ----------
        // Drop any entries from the Users table that don't have a UUID
        // ----------
        userDao.deleteWhere(Criterion.or(User.UUID.isNull(), User.UUID.eq(""), User.UUID.eq("0")));

        // --------------
        // Finally, ensure that all tag metadata entities have all important fields filled in
        // --------------
        Query incompleteQuery = Query.select(Metadata.PROPERTIES).where(Criterion.and(
                MetadataCriteria.withKey(TaskToTagMetadata.KEY),
                Criterion.or(TaskToTagMetadata.TASK_UUID.eq(0), TaskToTagMetadata.TASK_UUID.isNull(),
                        TaskToTagMetadata.TAG_UUID.eq(0), TaskToTagMetadata.TAG_UUID.isNull())));
        TodorooCursor<Metadata> incompleteMetadata = metadataService.query(incompleteQuery);
        try {
            Metadata m = new Metadata();
            for (incompleteMetadata.moveToFirst(); !incompleteMetadata.isAfterLast(); incompleteMetadata.moveToNext()) {
                m.clear(); // Need this since some properties may be null
                m.readFromCursor(incompleteMetadata);
                boolean changes = false;

                if (Constants.DEBUG)
                    Log.w(LOG_TAG, "Incomplete linking task " + m.getValue(Metadata.TASK) + " to " + m.getValue(TaskToTagMetadata.TAG_NAME));

                if (!m.containsNonNullValue(TaskToTagMetadata.TASK_UUID) || RemoteModel.NO_UUID.equals(m.getValue(TaskToTagMetadata.TASK_UUID))) {
                    if (Constants.DEBUG)
                        Log.w(LOG_TAG, "No task uuid");
                    updateTaskUuid(m);
                    changes = true;
                }

                if (!m.containsNonNullValue(TaskToTagMetadata.TAG_UUID) || RemoteModel.NO_UUID.equals(m.getValue(TaskToTagMetadata.TAG_UUID))) {
                    if (Constants.DEBUG)
                        Log.w(LOG_TAG, "No tag uuid");
                    updateTagUuid(m);
                    changes = true;
                }

                if (changes)
                    metadataService.save(m);

            }
        } finally {
            incompleteMetadata.close();
        }


        Preferences.setBoolean(PREF_SYNC_MIGRATION, true);
    }

    private interface UUIDAssertionExtras<TYPE extends RemoteModel> {
        void beforeSave(TYPE instance);
    }

    private <TYPE extends RemoteModel, OE extends OutstandingEntry<TYPE>> void assertUUIDsExist(Query query, TYPE instance, DatabaseDao<TYPE> dao, OutstandingEntryDao<OE> oeDao, OE oe, Property<?>[] propertiesForOutstanding, UUIDAssertionExtras<TYPE> extras) {
        TodorooCursor<TYPE> cursor = dao.query(query);
        try {
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                instance.readPropertiesFromCursor(cursor);
                boolean unsyncedModel = false;
                if (!instance.containsNonNullValue(RemoteModel.UUID_PROPERTY) || RemoteModel.NO_UUID.equals(instance.getValue(RemoteModel.UUID_PROPERTY))) {
                    // No remote id exists, just create a UUID
                    unsyncedModel = true;
                    instance.setValue(RemoteModel.UUID_PROPERTY, UUIDHelper.newUUID());
                }
                if (extras != null)
                    extras.beforeSave(instance);

                instance.putTransitory(SyncFlags.ACTFM_SUPPRESS_OUTSTANDING_ENTRIES, true);
                dao.saveExisting(instance);
                if (unsyncedModel && propertiesForOutstanding != null) {
                    createOutstandingEntries(instance.getId(), dao, oeDao, oe, propertiesForOutstanding);
                }
            }
        } finally {
            cursor.close();
        }
    }

    private <TYPE extends RemoteModel, OE extends OutstandingEntry<TYPE>> void createOutstandingEntries(long id, DatabaseDao<TYPE> dao, OutstandingEntryDao<OE> oeDao, OE oe, Property<?>[] propertiesForOutstanding) {
        TYPE instance = dao.fetch(id, propertiesForOutstanding);
        long now = DateUtilities.now();
        for (Property<?> property : propertiesForOutstanding) {
            oe.clear();
            oe.setValue(OutstandingEntry.ENTITY_ID_PROPERTY, id);
            oe.setValue(OutstandingEntry.COLUMN_STRING_PROPERTY, property.name);
            oe.setValue(OutstandingEntry.VALUE_STRING_PROPERTY, instance.getValue(property).toString());
            oe.setValue(OutstandingEntry.CREATED_AT_PROPERTY, now);
            oeDao.createNew(oe);
        }
    }

    private void updateTaskUuid(Metadata m) {
        long taskId = m.getValue(Metadata.TASK);
        Task task = taskDao.fetch(taskId, Task.UUID);
        if (task != null) {
            if (Constants.DEBUG)
                Log.w(LOG_TAG, "Linking with task uuid " + task.getValue(Task.UUID));
            m.setValue(TaskToTagMetadata.TASK_UUID, task.getValue(Task.UUID));
        } else {
            if (Constants.DEBUG)
                Log.w(LOG_TAG, "Task not found, deleting link");
            m.setValue(Metadata.DELETION_DATE, DateUtilities.now());
        }
    }

    private void updateTagUuid(Metadata m) {
        String tag = m.getValue(TaskToTagMetadata.TAG_NAME);
        TagData tagData = tagDataService.getTag(tag, TagData.UUID);
        if (tagData != null) {
            if (Constants.DEBUG)
                Log.w(LOG_TAG, "Linking with tag uuid " + tagData.getValue(TagData.UUID));
            m.setValue(TaskToTagMetadata.TAG_UUID, tagData.getValue(TagData.UUID));
        } else {
            if (Constants.DEBUG)
                Log.w(LOG_TAG, "Tag not found, deleting link");
            m.setValue(Metadata.DELETION_DATE, DateUtilities.now());
        }
    }

}
