/*
 * Copyright (c) 2009, SQL Power Group Inc.
 *
 * This file is part of SQL Power Library.
 *
 * SQL Power Library is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * SQL Power Library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package ca.sqlpower.dao;

import java.beans.PropertyChangeEvent;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.log4j.Logger;

import ca.sqlpower.dao.helper.SPPersisterHelper;
import ca.sqlpower.dao.helper.SPPersisterHelperFactory;
import ca.sqlpower.dao.session.SessionPersisterSuperConverter;
import ca.sqlpower.object.ObjectDependentException;
import ca.sqlpower.object.SPChildEvent;
import ca.sqlpower.object.SPListener;
import ca.sqlpower.object.SPObject;
import ca.sqlpower.util.SPSession;
import ca.sqlpower.util.SQLPowerUtils;
import ca.sqlpower.util.TransactionEvent;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

public class SPSessionPersister implements SPPersister {
	
	/**
	 * The god mode means that this listener will output
	 * events that are unconditional, always. This makes it the
	 * purveyor of the truth.
	 */
	private boolean godMode = false;
	
	private static final Logger logger = Logger.getLogger(SPSessionPersister.class);
	
	/**
	 * An {@link SPSession} to persist objects and properties onto.
	 */
	private SPSession session;
	
	/**
	 * A count of transactions, mainly to keep track of nested transactions.
	 */
	private int transactionCount = 0;
	
	/**
	 * Persisted property buffer, mapping of {@link SPObject} UUIDs to each
	 * individual persisted property
	 */
	private Multimap<String, PersistedSPOProperty> persistedProperties = LinkedListMultimap.create();

	/**
	 * This will be the list we will use to rollback persisted properties
	 */
	private List<PersistedPropertiesEntry> persistedPropertiesRollbackList = new LinkedList<PersistedPropertiesEntry>();
	
	/**
	 * Persisted {@link SPObject} buffer, contains all the data that was
	 * passed into the persistedObject call in the order of insertion
	 */
	protected List<PersistedSPObject> persistedObjects = new LinkedList<PersistedSPObject>();

	/**
	 * This will be the list we use to rollback persisted objects.
	 * It contains UUIDs of objects that were created.
	 */
	private List<PersistedObjectEntry> persistedObjectsRollbackList = new LinkedList<PersistedObjectEntry>();
	
	protected final Comparator<PersistedSPObject> persistedObjectComparator = new Comparator<PersistedSPObject>() {

		// If the two objects being compared are of the same type and are children of the same parent, the one with the lower index should go first.
		// Otherwise, the one with the smaller ancestor tree should go first (e.g. Report should go before Page).
		public int compare(PersistedSPObject o1, PersistedSPObject o2) {
			
			if (o1.getParentUUID() == null && o2.getParentUUID() == null) {
				return 0;
			} else if (o1.getParentUUID() == null) {
				return -1;
			} else if (o2.getParentUUID() == null) {
				return 1;
			} else if (o1.getParentUUID().equals(o2.getParentUUID()) && o1.getType().equals(o2.getType())) {
				return Integer.signum(o1.getIndex() - o2.getIndex());
			}
			
			List<PersistedSPObject> ancestorList1 = buildAncestorListFromPersistedObjects(o1);
			List<PersistedSPObject> ancestorList2 = buildAncestorListFromPersistedObjects(o2);
			
			PersistedSPObject previousAncestor = null;
			PersistedSPObject ancestor1 = null;
			PersistedSPObject ancestor2 = null;
			boolean compareWithAncestor = false;
			
			for (int i = 0, j = 0; i < ancestorList1.size() && j < ancestorList2.size(); i++, j++) {
				ancestor1 = ancestorList1.get(i);
				ancestor2 = ancestorList2.get(j);
				
				if (previousAncestor != null && !ancestor1.equals(ancestor2)) {
					compareWithAncestor = true;
					break;
				}
				
				previousAncestor = ancestor1;
			}
			
			if (!compareWithAncestor) {
				if (ancestorList1.size() < ancestorList2.size()) {
					ancestor1 = o1;
					ancestor2 = ancestorList2.get(ancestorList1.size());
				} else if (ancestorList1.size() > ancestorList2.size()) {
					ancestor1 = ancestorList1.get(ancestorList2.size());
					ancestor2 = o2;
				} else {
					ancestor1 = o1;
					ancestor2 = o2;
				}
			}
			
			int c;
			
			if (ancestor1.equals(ancestor2)) {
				c = ancestorList1.size() - ancestorList2.size();
				
			} else if (ancestor1.getType().equals(ancestor2.getType())) {
				c = ancestor1.getIndex() - ancestor2.getIndex();
				
			} else {
				// XXX The comparator should really never reach
				// this else block. However in the case that it does, compare by UUID.
				c = ancestor1.getUUID().compareTo(ancestor2.getUUID());
			}
			
			return Integer.signum(c);
		}
	};
	
	/**
	 * This comparator sorts buffered removeObject calls by each
	 * {@link SPObject} UUID. The UUIDs being compared must matchup with an
	 * existing SPObject in the {@link #root}. If it does not exist, it means
	 * that the SPObject has just been removed and this comparator is
	 * reshuffling the map.
	 * TODO We need a generic way of comparing {@link SPObject}s.
	 */
	protected final Comparator<String> removedObjectComparator = new Comparator<String>() {
		public int compare(String uuid1, String uuid2) {
			SPObject spo1 = SQLPowerUtils.findByUuid(root, uuid1, SPObject.class);
			SPObject spo2 = SQLPowerUtils.findByUuid(root, uuid2, SPObject.class);
			
			if (uuid1.equals(uuid2)) {
				return 0;
			} else if (spo1 == null && spo2 == null) {
				return uuid2.compareTo(uuid1);
			} else if (spo1 == null) {
				return -1;
			} else if (spo2 == null) {
				return 1;
			} else if (spo1.getParent() == null && spo2.getParent() == null) {
				return 0;
			} else if (spo1.getParent() == null) {
				return 1;
			} else if (spo2.getParent() == null) {
				return -1;
			} else if (spo1.equals(spo2)) {
				return 0;
			} else if (spo1.getParent().equals(spo2.getParent())) {
				List<? extends SPObject> siblings = spo1.getParent().getChildren();
				return Integer.signum(siblings.indexOf(spo2) - siblings.indexOf(spo1));
			}
				
			List<SPObject> ancestorList1 = SQLPowerUtils.getAncestorList(spo1);
			List<SPObject> ancestorList2 = SQLPowerUtils.getAncestorList(spo2);

			SPObject previousAncestor = null;
			SPObject ancestor1 = spo1;
			SPObject ancestor2 = spo2;
			boolean compareWithAncestor = false;

			for (int i = 0, j = 0; i < ancestorList1.size() && j < ancestorList2.size(); i++, j++) {
				ancestor1 = ancestorList1.get(i);
				ancestor2 = ancestorList2.get(j);

				if (previousAncestor != null && !ancestor1.equals(ancestor2)) {
					compareWithAncestor = true;
					break;
				}

				previousAncestor = ancestor1;

			}

			if (!compareWithAncestor) {
				if (ancestorList1.size() < ancestorList2.size()) {
					ancestor1 = spo1;
					ancestor2 = ancestorList2.get(ancestorList1.size());
				} else if (ancestorList1.size() > ancestorList2.size()) {
					ancestor1 = ancestorList1.get(ancestorList2.size());
					ancestor2 = spo2;
				} else {
					ancestor1 = spo1;
					ancestor2 = spo2;
				}
			}

			int c;

			if (ancestor1.equals(ancestor2)) {
				c = ancestorList2.size() - ancestorList1.size();

			} else if (ancestor1.getClass() == ancestor2.getClass()) {
				List<? extends SPObject> siblings = previousAncestor.getChildren();
				int index1 =  siblings.indexOf(ancestor1);
				int index2 = siblings.indexOf(ancestor2);

				c = index2 - index1;

			} else {
				// XXX The comparator should really never reach
				// this else block. However in the case that it does, compare by UUID.

				c = uuid2.compareTo(uuid1);
			}

			return Integer.signum(c);
		}
	};
	
	/**
	 * {@link SPObject} removal buffer, mapping of {@link SPObject} UUIDs
	 * to their parents
	 */
	protected Map<String, String> objectsToRemove = new TreeMap<String, String>(removedObjectComparator);
	
	/**
	 * This is the list we use to rollback object removal
	 */
	private List<RemovedObjectEntry> objectsToRemoveRollbackList = new LinkedList<RemovedObjectEntry>();
	
	/**
	 * This converter will do all of the converting for this persister.
	 */
	private final SessionPersisterSuperConverter converter;

	/**
	 * This factory creates {@link SPPersisterHelper}s for each type of
	 * persistable {@link SPObject} and allows persistObject, commitObject,
	 * commitProperty and findProperty method calls on it.
	 */
	private final SPPersisterHelperFactory persisterFactory;

	/**
	 * This root object is used to find other objects by UUID by walking the
	 * descendant tree when an object is required.
	 */
	private final SPObject root;
	
	/**
	 * Name of this persister (for debugging purposes).
	 */
	private final String name;

	private Thread currentThread;

	private boolean headingToWisconsin;
	
	/**
	 * Creates a session persister that can update any object at or a descendant
	 * of the given session's workspace object. If the persist call to this
	 * persister is involving an object that is not the workspace or descendant
	 * of the workspace in the given session an exception will be thrown
	 * depending on the call. See the specific method being called for more
	 * information about the exceptions that will be thrown.
	 */
	public SPSessionPersister(String name, SPSession session) {
		this(name, session, session.getWorkspace());
	}
	
	/**
	 * Creates a session persister that can update an object at or a descendant
	 * of the given root now. If the persist call involves an object that is
	 * outside of the scope of the root node and its descendant tree an
	 * exception will be thrown depending on the method called as the object
	 * will not be found.
	 */
	public SPSessionPersister(String name, SPSession session, SPObject root) {
		this.name = name;
		this.session = session;
		this.root = root;
		
		converter = new SessionPersisterSuperConverter(session, root);
		persisterFactory = new SPPersisterHelperFactory(null, converter) {
			
			@Override
			public SPPersisterHelper<?> getSPPersisterHelper(String simpleName) {
				// TODO Auto-generated method stub
				return null;
			}
		};
	}
	
	@Override
	public String toString() {
		return "SPSessionPersister \"" + name + "\"";
	}

	public void begin() throws SPPersistenceException {
		synchronized (session) {
			this.enforeThreadSafety();
			transactionCount++;
			
			if (logger.isDebugEnabled()) {
				logger.debug("spsp.begin(); - transaction count : " + transactionCount);
			}
		}
	}

	public void commit() throws SPPersistenceException {
		synchronized (session) {
			this.enforeThreadSafety();
			
			if (logger.isDebugEnabled()) {
				logger.debug("spsp.commit(); - transaction count : " + transactionCount);
			}
			
			final SPObject workspace = session.getWorkspace();
			synchronized (workspace) {
				try {
					workspace.setMagicEnabled(false);
					if (transactionCount == 0) {
						throw new SPPersistenceException(null,
							"Commit attempted while not in a transaction");
					}
	
					// Make sure the rollback lists are empty.
					this.objectsToRemoveRollbackList.clear();
					this.persistedObjectsRollbackList.clear();
					this.persistedPropertiesRollbackList.clear();
					
					if (transactionCount == 1) {
						if (logger.isDebugEnabled()) {
							logger.debug("Begin of commit phase...");
							logger.debug("Committing " + persistedObjects.size() + " new objects, " + 
									persistedProperties.size() + " changes to different property names, " +
									"and " + objectsToRemove.size() + " objects are being removed.");
						}
						
						workspace.begin("Begin batch transaction...");
						commitRemovals();
						commitObjects();
						commitProperties();
						workspace.commit();
						this.objectsToRemove.clear();
						this.objectsToRemoveRollbackList.clear();
						this.persistedObjects.clear();
						this.persistedObjectsRollbackList.clear();
						this.persistedProperties.clear();
						this.persistedPropertiesRollbackList.clear();
						this.currentThread = null;
						transactionCount = 0;
						
						if (logger.isDebugEnabled()) {
							logger.debug("...commit succeeded.");
						}
						
					} else {
						transactionCount--;
					}
				} catch (Throwable t) {
					logger.error("SPSessionPersister caught an exception while performing a commit operation. Will try to rollback...", t);
					this.rollback();
					throw new SPPersistenceException(null, t);
				} finally {
					workspace.setMagicEnabled(true);
				}
			}
		}
	}

	public void persistObject(String parentUUID, String type, String uuid,
			int index) throws SPPersistenceException {
		synchronized (session) {
			this.enforeThreadSafety();
			
			if (logger.isDebugEnabled()) {
				logger.debug(String.format(
						"spsp.persistObject(\"%s\", \"%s\", \"%s\", %d);", parentUUID,
						type, uuid, index));
			}
			
			if (transactionCount == 0) {
				this.rollback();
				throw new SPPersistenceException("Cannot persist objects while outside a transaction.");
			}
			SPObject objectToPersist = SQLPowerUtils.findByUuid(root, uuid, SPObject.class);
			if (exists(uuid) && objectToPersist.getClass() != root.getClass()) {
				this.rollback();
				throw new SPPersistenceException(uuid,
						"An SPObject with UUID " + uuid + " and type " + type
						+ " under parent with UUID " + parentUUID
						+ " already exists.");
			}

			PersistedSPObject pwo = new PersistedSPObject(parentUUID,
					type, uuid, index);
			persistedObjects.add(pwo);
		}
	}

	public void persistProperty(String uuid, String propertyName,
			DataType propertyType, Object oldValue, Object newValue)
			throws SPPersistenceException {
		if (transactionCount <= 0) {
			this.rollback();
			throw new SPPersistenceException("Cannot persist objects while outside a transaction.");
		}
		synchronized (session) {
			this.enforeThreadSafety();
			
			if (logger.isDebugEnabled()) {
				logger.debug(String.format(
						"spsp.persistProperty(\"%s\", \"%s\", DataType.%s, %s, %s);",
						uuid, propertyName, propertyType.name(), oldValue, newValue));
			}
			
			try {
				persistPropertyHelper(uuid, propertyName, propertyType, oldValue,
						newValue, this.godMode);
			} catch (SPPersistenceException e) {
				this.rollback();
				throw e;
			}
		}
	}

	public void persistProperty(String uuid, String propertyName,
			DataType propertyType, Object newValue)
			throws SPPersistenceException {
		if (transactionCount <= 0) {
			this.rollback();
			throw new SPPersistenceException("Cannot persist objects while outside a transaction.");
		}
		synchronized (session) {
			this.enforeThreadSafety();
			
			if (logger.isDebugEnabled()) {
				logger.debug(String.format(
						"spsp.persistProperty(\"%s\", \"%s\", DataType.%s, %s); // unconditional",
						uuid, propertyName, propertyType.name(),
						newValue));
			}
			
			try {
				if (newValue instanceof InputStream && !((InputStream) newValue).markSupported()) {
					newValue = new BufferedInputStream((InputStream) newValue);
				}
				persistPropertyHelper(uuid, propertyName, propertyType, newValue,
					newValue, true);
			} catch (SPPersistenceException e) {
				this.rollback();
				throw e;
			}
		}
	}
	
	/**
	 * Helper to persist a {@link SPObject} property given by its object
	 * UUID, property name, property type, expected old value, and new value.
	 * This can be done either conditionally or unconditionally based on which
	 * persistProperty method called this one.
	 * 
	 * @param uuid
	 *            The UUID of the {@link SPObject} to persist the property
	 *            upon
	 * @param propertyName
	 *            The property name
	 * @param propertyType
	 *            The property type
	 * @param oldValue
	 *            The expected old property value
	 * @param newValue
	 *            The new property value to persist
	 * @throws SPPersistenceException
	 *             Thrown if the property name is not known in this method.
	 */
	private void persistPropertyHelper(String uuid, String propertyName,
			DataType propertyType, Object oldValue, Object newValue,
			boolean unconditional) throws SPPersistenceException {
		
		if (!exists(uuid)) {
			throw new SPPersistenceException(uuid,
					"SPObject with UUID " + uuid + " could not be found." +
					" Was trying to set its property \"" + propertyName + "\" " +
					"to value \"" + newValue + "\".");
		}
		
		Object lastPropertyValueFound = null;
		
		for (PersistedSPOProperty wop : persistedProperties.get(uuid)) {
			if (propertyName.equals(wop.getPropertyName())) {
				lastPropertyValueFound = wop.getNewValue();
			}
		}
		
		Object propertyValue = null;
		SPObject spo = SQLPowerUtils.findByUuid(root, uuid,
				SPObject.class);
		
		if (lastPropertyValueFound != null) {
			if (!unconditional && !lastPropertyValueFound.equals(oldValue)) {
				throw new SPPersistenceException(uuid, "For property \""
						+ propertyName + "\", the expected property value \""
						+ oldValue
						+ "\" does not match with the actual property value \""
						+ lastPropertyValueFound + "\"");
			}
		} else {
			if (spo != null) {
				
				propertyValue = persisterFactory.findProperty(spo, propertyName);

				if (!unconditional && propertyValue != null && oldValue == null) {
					throw new SPPersistenceException(uuid, "For property \""
							+ propertyName + "\" on SPObject of type "
							+ spo.getClass() + " and UUID + " + spo.getUUID()
							+ ", the expected property value \"" + oldValue
							+ "\" does not match with the actual property value \""
							+ propertyValue + "\"");
				}
//			} else if (!unconditional) {
//				throw new SPPersistenceException(uuid, "Could not find the object with id " + 
//						uuid + " to set property " + propertyValue);
			}
		}
		
		if (spo != null) {
			persistedProperties.put(uuid, new PersistedSPOProperty(uuid,
					propertyName, propertyType, propertyValue, newValue, unconditional));
		} else {
			persistedProperties.put(uuid, new PersistedSPOProperty(uuid,
					propertyName, propertyType, oldValue, newValue, unconditional));
		}
	}

	public void removeObject(String parentUUID, String uuid)
			throws SPPersistenceException {
		synchronized (session) {
			this.enforeThreadSafety();
			logger.debug(String.format("spsp.removeObject(\"%s\", \"%s\");",
					parentUUID, uuid));
			if (this.transactionCount == 0) {
				logger.error("Remove Object attempted while not in a transaction. Rollback initiated.");
				this.rollback();
				throw new SPPersistenceException(uuid,"Remove Object attempted while not in a transaction. Rollback initiated.");
			}
			if (!exists(uuid)) {
				this.rollback();
				throw new SPPersistenceException(uuid,
						"Cannot remove the SPObject with UUID " + uuid
						+ " from parent UUID " + parentUUID
						+ " as it does not exist.");
			}
			objectsToRemove.put(uuid, parentUUID);
		}
	}

	public void rollback() {
		rollback(false);
	}
	
	public void rollback(boolean force) {
		final SPObject workspace = session.getWorkspace();
		synchronized (workspace) {
			if (this.headingToWisconsin) {
				return;
			}
			this.headingToWisconsin = true;
			if (!force) {
				this.enforeThreadSafety();
			}
			try {
				// We catch ANYTHING that comes out of here and rollback.
				// Some exceptions are Runtimes, so we must catch those too.
				workspace.begin("Rolling back changes.");
				rollbackProperties();
				rollbackCreations();
				rollbackRemovals();
				workspace.commit();
			} catch (Throwable t2) {
				// This is a major fuck up. We could not rollback so now we must restore
				// by whatever means
				logger.fatal("First try at restore failed.", t2);
				// TODO Monitor this
			} finally {
				this.objectsToRemove.clear();
				this.objectsToRemoveRollbackList.clear();
				this.persistedObjects.clear();
				this.persistedObjectsRollbackList.clear();
				this.persistedProperties.clear();
				this.persistedPropertiesRollbackList.clear();
				transactionCount = 0;
				this.headingToWisconsin = false;
				logger.debug("spsp.rollback(); - Killed all current transactions.");
			}
		}
	}

	/**
	 * Commits the removal of persisted {@link SPObject}s
	 * 
	 * @throws SPPersistenceException
	 *             Thrown if an {@link SPObject} could not be removed from its
	 *             parent.
	 */
	private void commitRemovals() throws SPPersistenceException {
		for (String uuid : objectsToRemove.keySet()) {
			SPObject spo = SQLPowerUtils.findByUuid(root, uuid,
					SPObject.class);
			SPObject parent = SQLPowerUtils.findByUuid(root, objectsToRemove
					.get(uuid), SPObject.class);
			try {
				int index = parent.getChildren().indexOf(spo);
				index -= parent.childPositionOffset(spo.getClass());
				parent.removeChild(spo);
				this.objectsToRemoveRollbackList.add(
					new RemovedObjectEntry(
						parent.getUUID(), 
						spo,
						index));
			} catch (IllegalArgumentException e) {
				throw new SPPersistenceException(uuid, e);
			} catch (ObjectDependentException e) {
				throw new SPPersistenceException(uuid, e);
			}
		}
		objectsToRemove.clear();
	}
	
	/**
	 * Commits the persisted {@link SPObject}s
	 * 
	 * @throws SPPersistenceException
	 */
	private void commitObjects() throws SPPersistenceException {
		Collections.sort(persistedObjects, persistedObjectComparator);
		
		for (PersistedSPObject pwo : persistedObjects) {
			if (pwo.isLoaded())
				continue;
			SPObject parent = SQLPowerUtils.findByUuid(root, pwo
					.getParentUUID(), SPObject.class);
			SPObject spo = persisterFactory.commitObject(pwo, persistedProperties, persistedObjects);
			if (spo != null) {
				SPListener removeChildOnAddListener = new SPListener() {
					public void propertyChange(PropertyChangeEvent arg0) {
						//do nothing
					}
					public void childRemoved(SPChildEvent e) {
						objectsToRemoveRollbackList.add(
								new RemovedObjectEntry(e.getSource().getUUID(), e.getChild(), e.getIndex()));
					}
					public void childAdded(SPChildEvent e) {
						//do nothing
					}
					public void transactionStarted(TransactionEvent e) {
						//do nothing
					}
					public void transactionRollback(TransactionEvent e) {
						//do nothing
					}
					public void transactionEnded(TransactionEvent e) {
						//do nothing
					}
				};
				parent.addSPListener(removeChildOnAddListener);
				// FIXME Terrible hack, see bug 2326
				parent.addChild(spo, Math.min(pwo.getIndex(), parent.getChildren(spo.getClass()).size()));
				parent.removeSPListener(removeChildOnAddListener);
				this.persistedObjectsRollbackList.add(
					new PersistedObjectEntry(
						parent.getUUID(), 
						spo.getUUID()));
			}
		}
		persistedObjects.clear();
	}
	
	/**
	 * Commits the persisted {@link SPObject} property values
	 * 
	 * @throws SPPersistenceException
	 *             Thrown if an invalid SPObject type has been persisted into
	 *             storage. This theoretically should not occur.
	 */
	private void commitProperties() throws SPPersistenceException {
		SPObject spo;
		String propertyName;
		Object newValue;

		for (String uuid : persistedProperties.keySet()) {
			spo = SQLPowerUtils.findByUuid(root, uuid, SPObject.class);
			if (spo == null) {
				throw new IllegalStateException("Couldn't locate object "
						+ uuid + " in session");
			}

			for (PersistedSPOProperty wop : persistedProperties.get(uuid)) {
				
				propertyName = wop.getPropertyName();
				newValue = wop.getNewValue();
				
				logger.debug("Applying property " + propertyName + " to " + spo.getClass().getSimpleName() + " at " + spo.getUUID());				
				persisterFactory.commitProperty(spo, propertyName, newValue);
				
				this.persistedPropertiesRollbackList.add(
					new PersistedPropertiesEntry(
						spo.getUUID(), //The uuid can be changed so using the currently set one.
						wop.getPropertyName(), 
						wop.getDataType(), 
						wop.getOldValue()));
			}
		}
		persistedProperties.clear();
	}
	
	/**
	 * Rolls back the removal of persisted {@link SPObject}s
	 * 
	 * @throws SPPersistenceException
	 *             Thrown if an {@link SPObject} could not be rolled back from
	 *             its parent.
	 */
	private void rollbackRemovals() throws IllegalStateException {
		// We must rollback in the inverse order the operations were performed.
		Collections.reverse(this.objectsToRemoveRollbackList);
		for (RemovedObjectEntry entry : this.objectsToRemoveRollbackList) {
			final String parentUuid = entry.getParentUUID();
			final SPObject objectToRestore = entry.getRemovedChild();
			final int index = entry.getIndex();
			final SPObject parent = SQLPowerUtils.findByUuid(root, parentUuid, SPObject.class);
			try {
				parent.addChild(objectToRestore, index);
			} catch (Throwable t) {
				// Keep going. We need to rollback as much as we can.
				logger.error("Cannot rollback " + entry.getRemovedChild() + " child removal", t);
			}
		}
	}
	
	private void rollbackProperties() throws SPPersistenceException {
		Collections.reverse(this.persistedPropertiesRollbackList);
		for (PersistedPropertiesEntry entry : this.persistedPropertiesRollbackList) {
			try {
				final String parentUuid = entry.getUUID();
				final String propertyName = entry.getPropertyName();
				final Object rollbackValue = entry.getRollbackValue();
				final SPObject parent = SQLPowerUtils.findByUuid(root, parentUuid, SPObject.class);
				if (parent != null) {
					persisterFactory.commitProperty(parent, propertyName, rollbackValue);
				}
			} catch (Throwable t) {
				// Keep going. We need to rollback as much as we can.
				logger.error("Cannot rollback change to " + entry.getPropertyName() + " to value " + entry.getRollbackValue(), t);
			}
		}
	}
	
	private void rollbackCreations() throws Exception {
		Collections.reverse(this.persistedObjectsRollbackList);
		for (PersistedObjectEntry entry : this.persistedObjectsRollbackList) {
			try {
				// We need to verify if the entry specifies a parent.
				// WabitWorkspaces don't have parents so we can't remove them really...
				if (entry.getParentId() != null) {
					final SPObject parent = SQLPowerUtils.findByUuid(root, entry.getParentId(), SPObject.class);
					final SPObject child = SQLPowerUtils.findByUuid(root, entry.getChildId(), SPObject.class);
					parent.removeChild(child);
				}
			} catch (Throwable t) {
				// Keep going. We need to rollback as much as we can.
				logger.error("Cannot rollback " + entry.getChildId() + " child creation", t);
			}
		}
	}
	
	/**
	 * Checks to see if a {@link SPObject} with a certain UUID exists
	 * 
	 * @param uuid
	 *            The UUID to search for
	 * @return Whether or not the {@link SPObject} exists
	 */
	private boolean exists(String uuid) {
		if (!objectsToRemove.containsKey(uuid)) {
			for (PersistedSPObject pwo : persistedObjects) {
				if (uuid.equals(pwo.getUUID())) {
					return true;
				}
			}
			if (SQLPowerUtils.findByUuid(root, uuid, SPObject.class) != null) {
				return true;
			}
		}
		return false;
	}
	
	public boolean isHeadingToWisconsin() {
		return headingToWisconsin;
	}

	/**
	 * This is part of the 'echo-cancellation' system to notify any
	 * {@link WorkspacePersisterListener} listening to the same session to
	 * ignore modifications to that session.
	 */
	public boolean isUpdatingWorkspace() {
		if (transactionCount > 0) {
			return true;
		} else if (transactionCount == 0) {
			return false;
		} else {
			this.rollback();
			throw new IllegalStateException("This persister is in an illegal state. transactionCount was :" + transactionCount);
		}
	}

	public void enforeThreadSafety() {
		if (this.currentThread == null) {
			this.currentThread = Thread.currentThread();
		} else {
			if (this.currentThread!=Thread.currentThread()) {
				this.rollback(true);
				throw new RuntimeException("A call from two different threads was detected. Callers of a sessionPersister should synchronize prior to opening transactions.");
			}
		}
	}

	/**
	 * Turns this persister as a preacher of the truth and always the truth. All
	 * calls are turned into unconditionals.
	 * 
	 * @param godMode
	 *            True or False
	 */
	public void setGodMode(boolean godMode) {
		this.godMode = godMode;
	}
	
	/**
	 * This static accessible method allows 
	 * @param session
	 * @param creations
	 * @param properties
	 * @param removals
	 * @throws SPPersistenceException
	 */
	public static void undoForSession(
			SPSession session,
			List<PersistedObjectEntry> creations,
			List<PersistedPropertiesEntry> properties,
			List<RemovedObjectEntry> removals) throws SPPersistenceException
	{
		SPSessionPersister persister = new SPSessionPersister("undoer", session);
		persister.setGodMode(true);
		persister.setObjectsToRemoveRollbackList(removals);
		persister.setPersistedObjectsRollbackList(creations);
		persister.setPersistedPropertiesRollbackList(properties);
		persister.rollback(true);
	}
	
	private void setObjectsToRemoveRollbackList(
			List<RemovedObjectEntry> objectsToRemoveRollbackList) {
		this.objectsToRemoveRollbackList = objectsToRemoveRollbackList;
	}
	
	private void setPersistedObjectsRollbackList(
			List<PersistedObjectEntry> persistedObjectsRollbackList) {
		this.persistedObjectsRollbackList = persistedObjectsRollbackList;
	}
	
	private void setPersistedPropertiesRollbackList(
			List<PersistedPropertiesEntry> persistedPropertiesRollbackList) {
		this.persistedPropertiesRollbackList = persistedPropertiesRollbackList;
	}
	
	/**
	 * Returns an ancestor list of {@link PersistedSPObject}s from a given
	 * child {@link PersistedSPObject}.
	 */
	private List<PersistedSPObject> buildAncestorListFromPersistedObjects(PersistedSPObject child) {
		List<PersistedSPObject> resultList = new ArrayList<PersistedSPObject>();

		// Iterate through list of persisted SPObjects to build an ancestor
		// list from objects that do not exist in the root yet.
		String uuid = child.getParentUUID();
		PersistedSPObject pwo;
		while ((pwo = findPersistedObjectByUUID(uuid)) != null) {
			resultList.add(0, pwo);
			uuid = pwo.getParentUUID();
		}

		// Iterate through list of existing SPObjects in the root and
		// build the rest of the ancestor list.
		SPObject spo = SQLPowerUtils.findByUuid(root, uuid, SPObject.class);
		if (spo != null) {
			resultList.add(0, createPersistedObjectFromSPObject(spo));
			List<SPObject> ancestorList = SQLPowerUtils.getAncestorList(spo);

			for (SPObject ancestor : ancestorList) {
				resultList.add(0, createPersistedObjectFromSPObject(ancestor));
			}
		}
		
		return resultList;
	}
	
	/**
	 * Returns a new {@link PersistedSPObject} based on a given {@link SPObject}.
	 */
	private PersistedSPObject createPersistedObjectFromSPObject(SPObject spo) {
		String parentUUID = null;
		int index = 0;
		
		if (spo.getParent() != null) {
			parentUUID = spo.getParent().getUUID();
			index = spo.getParent().getChildren(spo.getClass()).indexOf(spo);
		}
		
		return new PersistedSPObject(parentUUID, spo.getClass().getSimpleName(), 
				spo.getUUID(), index);
	}

	/**
	 * Returns an existing {@link PersistedSPObject} in the
	 * {@link #persistedObjects} list given by the UUID. If it does not exist,
	 * null is returned.
	 */
	private PersistedSPObject findPersistedObjectByUUID(String uuid) {
		if (uuid != null) {
			for (PersistedSPObject pwo : persistedObjects) {
				if (uuid.equals(pwo.getUUID())) {
					return pwo;
				}
			}
		}
		return null;
	}

}
