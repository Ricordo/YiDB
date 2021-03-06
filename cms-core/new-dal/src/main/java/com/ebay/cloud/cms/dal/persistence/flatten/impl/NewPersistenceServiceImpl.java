/*
Copyright [2013-2014] eBay Software Foundation

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/


/* 
Copyright 2012 eBay Software Foundation 

Licensed under the Apache License, Version 2.0 (the "License"); 
you may not use this file except in compliance with the License. 
You may obtain a copy of the License at 

    http://www.apache.org/licenses/LICENSE-2.0 

Unless required by applicable law or agreed to in writing, software 
distributed under the License is distributed on an "AS IS" BASIS, 
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
See the License for the specific language governing permissions and 
limitations under the License. 
*/ 

package com.ebay.cloud.cms.dal.persistence.flatten.impl;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import com.ebay.cloud.cms.config.CMSDBConfig;
import com.ebay.cloud.cms.dal.entity.AbstractEntityIDHelper;
import com.ebay.cloud.cms.dal.entity.IEntity;
import com.ebay.cloud.cms.dal.entity.expression.EntityExpressionEvaluator;
import com.ebay.cloud.cms.dal.entity.flatten.impl.FlattenEntityIDHelper;
import com.ebay.cloud.cms.dal.entity.flatten.impl.NewBsonEntity;
import com.ebay.cloud.cms.dal.entity.flatten.visitor.BsonPopulator;
import com.ebay.cloud.cms.dal.entity.flatten.visitor.FullBsonValidator;
import com.ebay.cloud.cms.dal.entity.flatten.visitor.PartialBsonValidator;
import com.ebay.cloud.cms.dal.exception.CmsDalException;
import com.ebay.cloud.cms.dal.exception.CmsDalException.DalErrCodeEnum;
import com.ebay.cloud.cms.dal.persistence.IPersistenceCommand;
import com.ebay.cloud.cms.dal.persistence.IPersistenceService;
import com.ebay.cloud.cms.dal.persistence.IRetrievalCommand;
import com.ebay.cloud.cms.dal.persistence.PersistenceContext;
import com.ebay.cloud.cms.dal.persistence.PersistenceContext.CollectionFinder;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.embed.EmbedCreateCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.embed.EmbedDeleteCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.embed.EmbedFieldDeleteCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.embed.EmbedFieldModifyCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.embed.EmbedGetCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.embed.EmbedModifyCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.embed.EmbedReplaceCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.root.RootCountCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.root.RootCreateCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.root.RootDeleteCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.root.RootFieldDeleteCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.root.RootFieldModifyCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.root.RootGetCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.root.RootMarkDeletedCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.root.RootModifyCommand;
import com.ebay.cloud.cms.dal.persistence.flatten.impl.root.RootReplaceCommand;
import com.ebay.cloud.cms.dal.persistence.impl.PersistenceService.Registration;
import com.ebay.cloud.cms.metadata.model.InternalFieldFactory.StatusEnum;
import com.ebay.cloud.cms.metadata.model.MetaAttribute;
import com.ebay.cloud.cms.metadata.model.MetaClass;
import com.ebay.cloud.cms.metadata.model.MetaField;
import com.ebay.cloud.cms.metadata.model.MetaField.CardinalityEnum;
import com.ebay.cloud.cms.mongo.MongoDataSource;
import com.ebay.cloud.cms.utils.CheckConditions;
import com.ebay.cloud.cms.utils.StringUtils;

/**
 * persistence service implementation
 * 
 * @author jianxu1, xjiang
 * @author liasu
 * 
 */
public class NewPersistenceServiceImpl implements IPersistenceService {

	private final MongoDataSource dataSource;
	private final CollectionFinder finder;
	private final FlattenEntityIDHelper helper;

    public NewPersistenceServiceImpl(MongoDataSource dataSource) {
        this.dataSource = dataSource;
        this.helper = FlattenEntityIDHelper.getInstance();
        this.finder = new NewCollectionFinder();
    }

    @Override
	public String create(IEntity entity, PersistenceContext context) {
	    NewBsonEntity bsonEntity = checkBsonEntity(entity);
	    
		setupContext(context);
		FullBsonValidator validator = new FullBsonValidator(true, helper);
		bsonEntity.traverse(validator);

		applyExpression(bsonEntity, context, false);

		MetaClass meta = bsonEntity.getMetaClass();
		IPersistenceCommand createCommand = null;
		if (meta.isEmbed()) {
			checkCreate(bsonEntity);
			createCommand = new EmbedCreateCommand(bsonEntity, helper);
		} else {
			createCommand = new RootCreateCommand(bsonEntity);
		}
		createCommand.execute(context);
		return bsonEntity.getId();
	}

    protected void setupContext(PersistenceContext context) {
        context.setMongoDataSource(dataSource);
        context.setCollectionFinder(finder);
    }
	
    private NewBsonEntity checkBsonEntity(IEntity entity) {
        CheckConditions.checkArgument(entity instanceof NewBsonEntity, "Incorrect call of passing non-bson entity to new bson persistent impl!");
        return (NewBsonEntity) entity;
    }

	private void checkCreate(NewBsonEntity entity) {
		if (!AbstractEntityIDHelper.isEmbedEntity(entity.getId())) {
			throw new CmsDalException(DalErrCodeEnum.STANDALONE_EMBED, String.format(
					"Could not create standalone entity for embed class %s!", entity.getType()));
		}
	}

	@Override
	public List<String> batchCreate(List<IEntity> entities, PersistenceContext context) {
		if (entities == null || entities.isEmpty()) {
			return Collections.emptyList();
		}

		setupContext(context);
		for (IEntity entity : entities) {
		    NewBsonEntity bsonEntity = checkBsonEntity(entity);
			if (bsonEntity.getId() == null) {
				throw new CmsDalException(DalErrCodeEnum.MISS_ID, "Id must be provided for batch create");
			}
			FullBsonValidator validator = new FullBsonValidator(true, helper);
			bsonEntity.traverse(validator);
			applyExpression(bsonEntity, context, false);
		}

		List<String> result = new LinkedList<String>();
		for (IEntity e : entities) {
		    NewBsonEntity bsonEntity = (NewBsonEntity)e;
			IPersistenceCommand createCommand = null;
			MetaClass meta = bsonEntity.getMetaClass();
			if (meta.isEmbed()) {
				checkCreate(bsonEntity);
				createCommand = new EmbedCreateCommand(bsonEntity, helper);
			} else {
				createCommand = new RootCreateCommand(bsonEntity);
			}
			createCommand.execute(context);
			result.add(e.getId());
		}
		return result;
	}

	@Override
	public NewBsonEntity get(IEntity queryEntity, PersistenceContext context) {
		String branchId = queryEntity.getBranchId();
		String entityId = queryEntity.getId();
		String entityType = queryEntity.getType();
		checkArguments(branchId, entityId, entityType);

		setupContext(context);

		MetaClass meta = queryEntity.getMetaClass();
		IRetrievalCommand getCommand = null;
		if (meta.isEmbed()) {
			if (!AbstractEntityIDHelper.isEmbedEntity(entityId)) {
				entityId = AbstractEntityIDHelper.generateEmbedIdByEmbedPath(queryEntity.getEmbedPath(), entityId);
			}
			getCommand = new EmbedGetCommand(branchId, entityId, meta, helper);
		} else {
			getCommand = new RootGetCommand(branchId, entityId, meta);
		}
		getCommand.execute(context);

		NewBsonEntity resultEntity = (NewBsonEntity) getCommand.getResult();
		if (resultEntity != null) {
			BsonPopulator populator = new BsonPopulator();
			resultEntity.traverse(populator);
		}
		return resultEntity;
	}

	@Override
	public void replace(IEntity entity, PersistenceContext context) {
	    NewBsonEntity bsonEntity  = checkBsonEntity(entity);
	    
		setupContext(context);

		FullBsonValidator validator = new FullBsonValidator(false, helper);
		bsonEntity.traverse(validator);

		applyExpression(bsonEntity, context, false);

		IPersistenceCommand replaceCommand = null;
		MetaClass meta = bsonEntity.getMetaClass();
		if (meta.isEmbed()) {
			replaceCommand = new EmbedReplaceCommand(bsonEntity, helper);
		} else {
			if (bsonEntity.getMetaClass().isEmbed()) {
				throw new CmsDalException(DalErrCodeEnum.STANDALONE_EMBED, String.format(
						"Could not replace standalone entity for embed class %s!", entity.getType()));
			}
			replaceCommand = new RootReplaceCommand(bsonEntity);
		}
		replaceCommand.execute(context);
	}

	@Override
	public void modify(IEntity entity, PersistenceContext context) {
	    NewBsonEntity bsonEntity  = checkBsonEntity(entity);

		setupContext(context);

		PartialBsonValidator validator = new PartialBsonValidator(helper);
		bsonEntity.traverse(validator);

		applyExpression(bsonEntity, context, true);
		IPersistenceCommand modifyCommand = null;
		MetaClass meta = bsonEntity.getMetaClass();
		if (meta.isEmbed()) {
			modifyCommand = new EmbedModifyCommand(bsonEntity, helper);
		} else {
			modifyCommand = new RootModifyCommand(bsonEntity);
		}
		modifyCommand.execute(context);
	}

	@Override
	public void batchUpdate(List<IEntity> entities, PersistenceContext context) {
		if (entities == null || entities.isEmpty()) {
			return;
		}

		for (IEntity entity : entities) {
		    NewBsonEntity bsonEntity = checkBsonEntity(entity);
			PartialBsonValidator validator = new PartialBsonValidator(helper);
			bsonEntity.traverse(validator);

			applyExpression(bsonEntity, context, true);
		}

		setupContext(context);
		for (IEntity entity : entities) {
            NewBsonEntity bsonEntity = (NewBsonEntity) entity;
			IPersistenceCommand modifyCommand;
			MetaClass meta = bsonEntity.getMetaClass();
			if (meta.isEmbed()) {
				modifyCommand = new EmbedModifyCommand(bsonEntity, helper);
			} else {
				modifyCommand = new RootModifyCommand(bsonEntity);
			}
			modifyCommand.execute(context);
		}
	}

	@Override
	public void delete(IEntity entity, PersistenceContext context) {
	    NewBsonEntity bsonEntity = checkBsonEntity(entity);
	    
		String branchId = bsonEntity.getBranchId();
		String entityId = bsonEntity.getId();
		String entityType = bsonEntity.getType();
		checkArguments(branchId, entityId, entityType);

		bsonEntity.setLastModified(new Date());
		bsonEntity.setStatus(StatusEnum.DELETED);

		setupContext(context);
		IPersistenceCommand deleteCommand = getDeleteCommand(bsonEntity, false);
		deleteCommand.execute(context);
	}
	
	@Override
	public void batchDelete(List<IEntity> entities, PersistenceContext context) {
		if (entities == null || entities.isEmpty()) {
			return;
		}
			
		for (IEntity entity : entities) {
			delete(entity, context);
		}
	}

	@Override
	public void ensureIndex(List<MetaClass> meta, PersistenceContext context, boolean onMainBranch) {
	    CheckConditions.checkArgument(!(meta == null || meta.isEmpty()), "Metadata can not be null");
		setupContext(context);
		IndexBuildCommand command = new IndexBuildCommand(meta, onMainBranch);
		command.execute(context);
	}

	@Override
	public void markDeleted(IEntity entity, PersistenceContext context) {
	    NewBsonEntity bsonEntity = checkBsonEntity(entity);
	    
		String branchId = bsonEntity.getBranchId();
		String entityId = bsonEntity.getId();
		String entityType = bsonEntity.getType();
		checkArguments(branchId, entityId, entityType);

		// mark delete field changes
		setDeleteBsonEntity(bsonEntity);

		setupContext(context);
		IPersistenceCommand deleteCommand = getDeleteCommand(bsonEntity, true);
		deleteCommand.execute(context);
	}

	private void setDeleteBsonEntity(NewBsonEntity entity) {
		entity.setLastModified(new Date());
		entity.setVersion(IEntity.NO_VERSION);
		entity.setStatus(StatusEnum.DELETED);
	}

	private IPersistenceCommand getDeleteCommand(NewBsonEntity entity, boolean markDelete) {
		IPersistenceCommand deleteCommand = null;
		MetaClass meta = entity.getMetaClass();
		if (meta.isEmbed()) {
			deleteCommand = new EmbedDeleteCommand(entity, helper);
		} else {
			if (markDelete) {
				deleteCommand = new RootMarkDeletedCommand(entity);
			} else {
				deleteCommand = new RootDeleteCommand(entity);
			}
		}
		return deleteCommand;
	}

	private final void checkArguments(String branchId, String entityId, String entityType) {
	    CheckConditions.checkArgument(!StringUtils.isNullOrEmpty(branchId), "Miss valid branch id");
	    CheckConditions.checkArgument(!StringUtils.isNullOrEmpty(entityId), "Miss valid entity id");
	    CheckConditions.checkArgument(!StringUtils.isNullOrEmpty(entityType), "Miss valid entity type");
	}

	private final void applyExpression(NewBsonEntity entity, PersistenceContext context, boolean needDBEntity) {
		NewBsonEntity dbEntity = null;
		if (needDBEntity) {
			PersistenceContext newContext = new PersistenceContext(context);
			dbEntity = get(entity, newContext);
		}

		executeExpression(entity, dbEntity);
	}

	private final void applyExpression(NewBsonEntity entity, PersistenceContext context, String fieldName) {
		PersistenceContext newContext = new PersistenceContext(context);
		NewBsonEntity dbEntity = get(entity, newContext);
		dbEntity.removeField(fieldName);
		executeExpression(entity, dbEntity);
	}

	private final void executeExpression(NewBsonEntity entity, NewBsonEntity dbEntity) {
		MetaClass metadata = entity.getMetaClass();
		if (metadata.hasExpressionFields() || metadata.hasValidationFields()) {
			// TODO: optimize it for RootModifyCommand that also need db entity
			CMSDBConfig dbConfig = new CMSDBConfig(dataSource);
			EntityExpressionEvaluator visitor = new EntityExpressionEvaluator(dbEntity, dbConfig);
			entity.traverse(visitor);
		}
	}

	@Override
	public void modifyField(IEntity entity, String fieldName, PersistenceContext context) {
	    NewBsonEntity bsonEntity = checkBsonEntity(entity);
	    
		checkFieldArgument(bsonEntity, fieldName);

		setupContext(context);
		PartialBsonValidator validator = new PartialBsonValidator(helper);
		bsonEntity.traverse(validator);

		applyExpression(bsonEntity, context, true);
		IPersistenceCommand modifyCommand = null;
		MetaClass meta = bsonEntity.getMetaClass();
		if (meta.isEmbed()) {
			modifyCommand = new EmbedFieldModifyCommand(bsonEntity, fieldName, helper);
		} else {
			modifyCommand = new RootFieldModifyCommand(bsonEntity, fieldName);
		}
		modifyCommand.execute(context);
	}

	@Override
	public void deleteField(IEntity entity, String fieldName, PersistenceContext context) {
	    NewBsonEntity bsonEntity = checkBsonEntity(entity);
	    
		checkFieldArgument(bsonEntity, fieldName);
		setupContext(context);

		MetaField field = bsonEntity.getMetaClass().getFieldByName(fieldName);
		if (field instanceof MetaAttribute) {
			String expr = ((MetaAttribute) field).getExpression();
			if (!StringUtils.isNullOrEmpty(expr)) {
				throw new CmsDalException(DalErrCodeEnum.CANNOT_DELETE_EXPRESSION_FIELD, String.format(
						"Could not delete expression field %s!", fieldName));
			}
		}

		Date lastmodified = new Date();
		bsonEntity.setLastModified(lastmodified);

		boolean isMany = field.getCardinality() == CardinalityEnum.Many;
		if (isMany) {
			// force update the field properties for delete array with contents
			boolean hasDeltaContent = bsonEntity.hasField(fieldName);
			CheckConditions.checkArgument(!hasDeltaContent, "Only support remove the whole array by deleteField!");
		} else {
		    CheckConditions.checkArgument(!field.isMandatory(), "Could not delete mandatory field!");
		}

		applyExpression(bsonEntity, context, fieldName);
		IPersistenceCommand command = null;
		MetaClass meta = bsonEntity.getMetaClass();
		if (meta.isEmbed()) {
            command = new EmbedFieldDeleteCommand(bsonEntity, fieldName, helper);
		} else {
			command = new RootFieldDeleteCommand(bsonEntity, fieldName);
		}
		command.execute(context);
	}

	private void checkFieldArgument(NewBsonEntity entity, String fieldName) {
		checkArguments(entity.getBranchId(), entity.getId(), entity.getType());
		CheckConditions.checkArgument(fieldName != null && !fieldName.isEmpty(), "fieldName can not be empty!");
		MetaField field = entity.getMetaClass().getFieldByName(fieldName);
		CheckConditions.checkArgument(field != null,
				MessageFormat.format("Can not find field {0} on meta class!", fieldName));
		CheckConditions.checkArgument(!field.isInternal(),
				MessageFormat.format("Can not update internal field {0}!", fieldName));
		CheckConditions.checkArgument(!field.isConstant(),
				MessageFormat.format("Can not update constant field {0}!", fieldName));
	}

	@Override
	public long count(MetaClass meta, List<String> refOids, String branchId, PersistenceContext context) {
		setupContext(context);
		IRetrievalCommand command = new RootCountCommand(meta, refOids, branchId);
		command.execute(context);
		return ((Long) command.getResult()).longValue();
    }

    @Override
    public List<Registration> getRegistrations() {
        return Arrays.asList(new Registration("flatten", this, NewBsonEntity.class, NewDalEntityFactory.getInstance(),
                NewDalSearchStrategy.getInstance(), FlattenEntityIDHelper.getInstance(), finder));
    }

}
