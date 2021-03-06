/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index.mapper.internal;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocValuesTermsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.loader.SettingsLoader;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.fielddata.FieldDataType;
import org.elasticsearch.index.mapper.ContentPath;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperBuilders;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MetadataFieldMapper;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.Uid;
import org.elasticsearch.index.mapper.core.StringFieldMapper;
import org.elasticsearch.index.query.QueryShardContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeMapValue;

/**
 *
 */
public class ParentFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_parent";
    public static final String CONTENT_TYPE = "_parent";

    public static class Defaults {
        public static final String NAME = ParentFieldMapper.NAME;

        public static final ParentFieldType FIELD_TYPE = new ParentFieldType();

        static {
            FIELD_TYPE.setIndexOptions(IndexOptions.NONE);
            FIELD_TYPE.setHasDocValues(true);
            FIELD_TYPE.setDocValuesType(DocValuesType.SORTED);
            FIELD_TYPE.freeze();
        }
    }

    public static class Builder extends MetadataFieldMapper.Builder<Builder, ParentFieldMapper> {

        private String parentType;

        private final String documentType;

        public Builder(String documentType) {
            super(Defaults.NAME, new ParentFieldType(Defaults.FIELD_TYPE, documentType), Defaults.FIELD_TYPE);
            this.documentType = documentType;
            builder = this;
        }

        public Builder type(String type) {
            this.parentType = type;
            return builder;
        }

        @Override
        public ParentFieldMapper build(BuilderContext context) {
            if (parentType == null) {
                throw new MapperParsingException("[_parent] field mapping must contain the [type] option");
            }
            name = joinField(parentType);
            setupFieldType(context);
            return new ParentFieldMapper(createParentJoinFieldMapper(documentType, context), fieldType, parentType, context.indexSettings());
        }
    }

    public static class TypeParser implements MetadataFieldMapper.TypeParser {
        @Override
        public MetadataFieldMapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            Builder builder = new Builder(parserContext.type());
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = Strings.toUnderscoreCase(entry.getKey());
                Object fieldNode = entry.getValue();
                if (fieldName.equals("type")) {
                    builder.type(fieldNode.toString());
                    iterator.remove();
                } else if (fieldName.equals("fielddata")) {
                    // Only take over `loading`, since that is the only option now that is configurable:
                    Map<String, String> fieldDataSettings = SettingsLoader.Helper.loadNestedFromMap(nodeMapValue(fieldNode, "fielddata"));
                    if (fieldDataSettings.containsKey(MappedFieldType.Loading.KEY)) {
                        Settings settings = settingsBuilder().put(MappedFieldType.Loading.KEY, fieldDataSettings.get(MappedFieldType.Loading.KEY)).build();
                        builder.fieldDataSettings(settings);
                    }
                    iterator.remove();
                }
            }
            return builder;
        }

        @Override
        public MetadataFieldMapper getDefault(Settings indexSettings, MappedFieldType fieldType, String typeName) {
            StringFieldMapper parentJoinField = createParentJoinFieldMapper(typeName, new BuilderContext(indexSettings, new ContentPath(0)));
            MappedFieldType childJoinFieldType = Defaults.FIELD_TYPE.clone();
            childJoinFieldType.setName(joinField(null));
            return new ParentFieldMapper(parentJoinField, childJoinFieldType, null, indexSettings);
        }
    }

    static StringFieldMapper createParentJoinFieldMapper(String docType, BuilderContext context) {
        StringFieldMapper.Builder parentJoinField = MapperBuilders.stringField(joinField(docType));
        parentJoinField.indexOptions(IndexOptions.NONE);
        parentJoinField.docValues(true);
        parentJoinField.fieldType().setDocValuesType(DocValuesType.SORTED);
        parentJoinField.fieldType().setFieldDataType(null);
        return parentJoinField.build(context);
    }

    static final class ParentFieldType extends MappedFieldType {

        final String documentType;

        public ParentFieldType() {
            setFieldDataType(new FieldDataType(NAME, settingsBuilder().put(MappedFieldType.Loading.KEY, Loading.EAGER_VALUE)));
            documentType = null;
        }

        ParentFieldType(ParentFieldType ref, String documentType) {
            super(ref);
            this.documentType = documentType;
        }

        private ParentFieldType(ParentFieldType ref) {
            super(ref);
            this.documentType = ref.documentType;
        }

        @Override
        public MappedFieldType clone() {
            return new ParentFieldType(this);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        /**
         * We don't need to analyzer the text, and we need to convert it to UID...
         */
        @Override
        public boolean useTermQueryWithQueryString() {
            return true;
        }

        @Override
        public Query termQuery(Object value, @Nullable QueryShardContext context) {
            return termsQuery(Collections.singletonList(value), context);
        }

        @Override
        public Query termsQuery(List values, @Nullable QueryShardContext context) {
            BytesRef[] ids = new BytesRef[values.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = indexedValueForSearch(values.get(i));
            }
            BooleanQuery.Builder query = new BooleanQuery.Builder();
            query.add(new DocValuesTermsQuery(name(), ids), BooleanClause.Occur.MUST);
            query.add(new TermQuery(new Term(TypeFieldMapper.NAME, documentType)), BooleanClause.Occur.FILTER);
            return query.build();
        }
    }

    private final String parentType;
    // has no impact of field data settings, is just here for creating a join field,
    // the parent field mapper in the child type pointing to this type determines the field data settings for this join field
    private final StringFieldMapper parentJoinField;

    private ParentFieldMapper(StringFieldMapper parentJoinField, MappedFieldType childJoinFieldType, String parentType, Settings indexSettings) {
        super(NAME, childJoinFieldType, Defaults.FIELD_TYPE, indexSettings);
        this.parentType = parentType;
        this.parentJoinField = parentJoinField;
    }

    public MappedFieldType getParentJoinFieldType() {
        return parentJoinField.fieldType();
    }

    public String type() {
        return parentType;
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
    }

    @Override
    public void postParse(ParseContext context) throws IOException {
        if (context.sourceToParse().flyweight() == false) {
            parse(context);
        }
    }

    @Override
    protected void parseCreateField(ParseContext context, List<Field> fields) throws IOException {
        boolean parent = context.docMapper().isParent(context.type());
        if (parent) {
            fields.add(new SortedDocValuesField(parentJoinField.fieldType().name(), new BytesRef(context.id())));
        }

        if (!active()) {
            return;
        }

        if (context.parser().currentName() != null && context.parser().currentName().equals(Defaults.NAME)) {
            // we are in the parsing of _parent phase
            String parentId = context.parser().text();
            context.sourceToParse().parent(parentId);
            fields.add(new SortedDocValuesField(fieldType.name(), new BytesRef(parentId)));
        } else {
            // otherwise, we are running it post processing of the xcontent
            String parsedParentId = context.doc().get(Defaults.NAME);
            if (context.sourceToParse().parent() != null) {
                String parentId = context.sourceToParse().parent();
                if (parsedParentId == null) {
                    if (parentId == null) {
                        throw new MapperParsingException("No parent id provided, not within the document, and not externally");
                    }
                    // we did not add it in the parsing phase, add it now
                    fields.add(new SortedDocValuesField(fieldType.name(), new BytesRef(parentId)));
                } else if (parentId != null && !parsedParentId.equals(Uid.createUid(context.stringBuilder(), parentType, parentId))) {
                    throw new MapperParsingException("Parent id mismatch, document value is [" + Uid.createUid(parsedParentId).id() + "], while external value is [" + parentId + "]");
                }
            }
        }
        // we have parent mapping, yet no value was set, ignore it...
    }

    public static String joinField(String parentType) {
        return ParentFieldMapper.NAME + "#" + parentType;
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public Iterator<Mapper> iterator() {
        return Collections.<Mapper>singleton(parentJoinField).iterator();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        if (!active()) {
            return builder;
        }
        boolean includeDefaults = params.paramAsBoolean("include_defaults", false);

        builder.startObject(CONTENT_TYPE);
        builder.field("type", parentType);
        if (includeDefaults || joinFieldHasCustomFieldDataSettings()) {
            builder.field("fielddata", (Map) fieldType().fieldDataType().getSettings().getAsMap());
        }
        builder.endObject();
        return builder;
    }

    private boolean joinFieldHasCustomFieldDataSettings() {
        return fieldType != null && fieldType.fieldDataType() != null && fieldType.fieldDataType().equals(Defaults.FIELD_TYPE.fieldDataType()) == false;
    }

    @Override
    protected void doMerge(Mapper mergeWith, boolean updateAllTypes) {
        super.doMerge(mergeWith, updateAllTypes);
        ParentFieldMapper fieldMergeWith = (ParentFieldMapper) mergeWith;
        if (Objects.equals(parentType, fieldMergeWith.parentType) == false) {
            throw new IllegalArgumentException("The _parent field's type option can't be changed: [" + parentType + "]->[" + fieldMergeWith.parentType + "]");
        }

        List<String> conflicts = new ArrayList<>();
        fieldType().checkCompatibility(fieldMergeWith.fieldType, conflicts, true);
        if (conflicts.isEmpty() == false) {
            throw new IllegalArgumentException("Merge conflicts: " + conflicts);
        }

        if (active()) {
            fieldType = fieldMergeWith.fieldType.clone();
        }
    }

    /**
     * @return Whether the _parent field is actually configured.
     */
    public boolean active() {
        return parentType != null;
    }

}
