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

package ca.sqlpower.object;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.log4j.Logger;

import ca.sqlpower.object.SPChildEvent.EventType;
import ca.sqlpower.util.SPSession;
import ca.sqlpower.util.SessionNotFoundException;
import ca.sqlpower.util.TransactionEvent;

public abstract class AbstractSPObject implements SPObject {
	
    private static final Logger logger = Logger.getLogger(SPObject.class);
	
    protected final List<SPListener> listeners = 
        Collections.synchronizedList(new ArrayList<SPListener>());
    
	private SPObject parent;
	private String name;
	
	public AbstractSPObject() {
		this(null);
	}
	
	/**
	 * The uuid string passed in must be the toString representation of the UUID
	 * for this object. If the uuid string given is null then a new UUID will be
	 * automatically generated.
	 */
    public AbstractSPObject(String uuid) {
    	if (uuid == null) {
    	    generateNewUUID();
    	} else {
    		this.uuid = uuid;
    	}
    }
	
    /**
     * This UUID is for saving and loading to allow saved files to be diff friendly.
     */
    protected String uuid;

    public boolean allowsChildType(Class<? extends SPObject> type) {
    	for (Class<? extends SPObject> child : getAllowedChildTypes()) {
    		if (child.isAssignableFrom(type)) {
    			return true;
    		}
    	}
    	return false;
    }
    
	public final void addChild(SPObject child, int index)
			throws IllegalArgumentException {
		if (!allowsChildType(child.getClass())) {
			throw new IllegalArgumentException(child.getClass() + " is not a valid child type of " + this.getClass());
		}
		
		child.setParent(this);
		addChildImpl(child, index);
	}
	
    /**
     * This is the object specific implementation of
     * {@link #addChild(SPObject, int)}. There are checks in the
     * {@link #addChild(SPObject, int))} method to ensure that the object given
     * here is a valid child type of this object.
     * <p>
     * This method should be overwritten if children are allowed.
     * 
     * @param child
     *            The child to add to this object.
     * @param index
     *            The index to add the child at.
     */
	protected void addChildImpl(SPObject child, int index) {
		throw new UnsupportedOperationException("This SPObject item cannot have children. " +
				"This class is " + getClass() + " and trying to add " + child.getName() + 
				" of type " + child.getClass());
	}

	public void addSPListener(SPListener l) {
    	if (l == null) {
    		throw new NullPointerException("Cannot add child listeners that are null.");
    	}
    	synchronized (listeners) {
    	    listeners.add(l);
    	}
	}

	/**
	 * Default cleanup method that does nothing. Override and implement this
	 * method if cleanup is necessary.
	 */
	public CleanupExceptions cleanup() {
	    return new CleanupExceptions();
	}

	public void generateNewUUID() {
		uuid = UUID.randomUUID().toString();
	}

	public <T extends SPObject> List<T> getChildren(Class<T> type) {
		List<T> children = new ArrayList<T>();
		for (SPObject child : getChildren()) {
			if (type.isAssignableFrom(child.getClass())) {
				children.add(type.cast(child));
			}
		}
		return children;
	}

	
	public String getName() {
		return name;
	}

	public SPObject getParent() {
		return parent;
	}

	public String getUUID() {
		return uuid;
	}


	public boolean removeChild(SPObject child)
			throws ObjectDependentException, IllegalArgumentException {
	    if (!getChildren().contains(child)) {
	        throw new IllegalArgumentException("Child object " + child.getName() + " of type " + child.getClass()
	                + " is not a child of " + getName() + " of type " + getClass());
	    }
	    
	    return removeChildImpl(child);
	}
	
    /**
     * This is the object specific implementation of removeChild. There are
     * checks in the removeChild method to ensure the child being removed has no
     * dependencies and is a child of this object.
     * 
     * @see #removeChild(SPObject)
     */
	protected abstract boolean removeChildImpl(SPObject child);

	public void removeSPListener(SPListener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
	}

	public void rollback(String message) {
		fireTransactionRollback(message);
	}

	public void setName(String name) {
		String oldName = this.name;
		this.name = name;
		firePropertyChange("name", oldName, name);
	}

	public void setParent(SPObject parent) {
		SPObject oldParent = this.parent;
		this.parent = parent;
		firePropertyChange("parent", oldParent, parent);
	}

	public void setUUID(String uuid) {
		String oldUUID = this.uuid;
		
		if (uuid == null) {
			generateNewUUID();
		} else {
			this.uuid = uuid;
		}
		
		firePropertyChange("UUID", oldUUID, this.uuid);
	}

	/**
	 * Gets the current session by passing the request up the tree.
	 */
	public SPSession getSession() throws SessionNotFoundException {
		// The root object of the tree model should have a reference back to the
		// session (like WabitWorkspace), and should therefore override this
		// method. If it does not, a SessionNotFoundException will be thrown.
		if (getParent() != null) {
			return getParent().getSession();
		} else {
			throw new SessionNotFoundException("Root object does not have a session reference");
		}
	}
	
    /**
     * Fires a child added event to all child listeners. The child should have
     * been added by the calling code already.
     * 
     * @param type
     *            The canonical type of the child being added
     * @param child
     *            The child object that was added
     * @param index
     *            The index of the added child within its own child list (this
     *            will be converted to the overall child position before the
     *            event object is constructed).
     * @return The child event that was fired or null if no event was fired, for
     *         testing purposes.
     */
    protected SPChildEvent fireChildAdded(Class<? extends SPObject> type, SPObject child, int index) {
    	logger.debug("Child Added: " + type + " notifying " + listeners.size() + " listeners");
    	
    	if (!isForegroundThread()) {
    		throw new IllegalStateException("Event for adding the child " + child.getName() + 
    				" must fired on the foreground thread.");
    	}
    	
        synchronized(listeners) {
            if (listeners.isEmpty()) return null;
        }
        final SPChildEvent e = new SPChildEvent(this, type, child, index, EventType.ADDED);
        synchronized(listeners) {
        	for (int i = listeners.size() - 1; i >= 0; i--) {
        		final SPListener listener = listeners.get(i);
        		listener.childAdded(e);
        	}
        }
        return e;
    }
    
    /**
     * Fires a child removed event to all child listeners. The child should have
     * been removed by the calling code.
     * 
     * @param type
     *            The canonical type of the child being removed
     * @param child
     *            The child object that was removed
     * @param index
     *            The index that the removed child was at within its own child
     *            list (this will be converted to the overall child position
     *            before the event object is constructed).
     * @return The child event that was fired or null if no event was fired, for
     *         testing purposes.
     */
    protected SPChildEvent fireChildRemoved(Class<? extends SPObject> type, SPObject child, int index) {
    	logger.debug("Child Removed: " + type + " notifying " + listeners.size() + " listeners: " + listeners);
    	
    	if (!isForegroundThread()) {
    		throw new IllegalStateException("Event for removing the child " + child.getName() + 
    				" must fired on the foreground thread.");
    	}
    	
        synchronized(listeners) {
            if (listeners.isEmpty()) return null;
        }
        final SPChildEvent e = new SPChildEvent(this, type, child, index, EventType.REMOVED);
        synchronized(listeners) {
        	for (int i = listeners.size() - 1; i >= 0; i--) {
        		final SPListener listener = listeners.get(i);
        		listener.childRemoved(e);
        	}
        }
        return e;
    }
    
    /**
     * Fires a property change on the foreground thread as defined by the
     * current session being used.
     * 
     * @return The property change event that was fired or null if no event was
     *         fired, for testing purposes.
     */
    protected PropertyChangeEvent firePropertyChange(final String propertyName, final boolean oldValue, 
            final boolean newValue) {
    	if (oldValue == newValue) return null;
    	
    	if (!isForegroundThread()) {
    		throw new IllegalStateException("Event for property change " + propertyName + 
    				" must fired on the foreground thread.");
    	}
        synchronized(listeners) {
            if (listeners.size() == 0) return null;
        }
        final PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
        synchronized(listeners) {
        	for (int i = listeners.size() - 1; i >= 0; i--) {
        		listeners.get(i).propertyChange(evt);
        	}
        }
        return evt;
    }

    /**
     * Fires a property change on the foreground thread as defined by the
     * current session being used.
     * 
     * @return The property change event that was fired or null if no event was
     *         fired, for testing purposes.
     */
    protected PropertyChangeEvent firePropertyChange(final String propertyName, final int oldValue, 
            final int newValue) {
    	if (oldValue == newValue) return null;
    	
    	if (!isForegroundThread()) {
    		throw new IllegalStateException("Event for property change " + propertyName + 
    				" must fired on the foreground thread.");
    	}
    	
        synchronized(listeners) {
            if (listeners.size() == 0) return null;
        }
        final PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
        synchronized(listeners) {
        	for (int i = listeners.size() - 1; i >= 0; i--) {
        		listeners.get(i).propertyChange(evt);
        	}
        }
        return evt;
    }

    /**
     * Fires a property change on the foreground thread as defined by the
     * current session being used.
     * 
     * @return The property change event that was fired or null if no event was
     *         fired, for testing purposes.
     */
    protected PropertyChangeEvent firePropertyChange(final String propertyName, final Object oldValue, 
            final Object newValue) {
    	if ((oldValue == null && newValue == null)
    			|| (oldValue != null && oldValue.equals(newValue))) return null; 
    	
    	if (!isForegroundThread()) {
    		throw new IllegalStateException("Event for property change " + propertyName + 
    				" must fired on the foreground thread.");
    	}
    	
        synchronized(listeners) {
            if (listeners.size() == 0) return null;
            if (logger.isDebugEnabled()) {
                logger.debug("Firing property change \"" + propertyName
                        + "\" to " + listeners.size() + " listeners: "
                        + listeners);
            }
        }
        final PropertyChangeEvent evt = new PropertyChangeEvent(this, propertyName, oldValue, newValue);
        synchronized(listeners) {
        	for (int i = listeners.size() - 1; i >= 0; i--) {
        		listeners.get(i).propertyChange(evt);
        	}
        }
        return evt;
    }
    
    /**
     * Fires a transaction started event with a message indicating the
     * reason/type of the transaction.
     * 
     * @return The event that was fired or null if no event was fired, for
     *         testing purposes.
     */
    protected TransactionEvent fireTransactionStarted(final String message) {
    	if (!isForegroundThread()) {
    		throw new IllegalStateException("Event for a transaction start" + 
    				" must fired on the foreground thread.");
    	}
        synchronized (listeners) {
            if (listeners.size() == 0) return null;            
        }
        final TransactionEvent evt = TransactionEvent.createStartTransactionEvent(this, message);
        synchronized (listeners) {
        	for (int i = listeners.size() - 1; i >= 0; i--) {
        		listeners.get(i).transactionStarted(evt);
        	}
        }
        return evt;
    }

    /**
     * Fires a transaction ended event.
     * 
     * @return The event that was fired or null if no event was fired, for
     *         testing purposes.
     */
    protected TransactionEvent fireTransactionEnded() {
    	if (!isForegroundThread()) {
    		throw new IllegalStateException("Event for a transaction end" + 
    				" must fired on the foreground thread.");
    	}
        synchronized (listeners) {
            if (listeners.size() == 0) return null;            
        }
        final TransactionEvent evt = TransactionEvent.createEndTransactionEvent(this);
        synchronized (listeners) {
        	for (int i = listeners.size() - 1; i >= 0; i--) {
        		listeners.get(i).transactionEnded(evt);
        	}
        }
        return evt;
    }

    /**
     * Fires a transaction rollback event with a message indicating the
     * reason/type of the rollback.
     * 
     * @return The event that was fired or null if no event was fired, for
     *         testing purposes.
     */
    protected TransactionEvent fireTransactionRollback(final String message) {
    	if (!isForegroundThread()) {
    		throw new IllegalStateException("Event for a transaction rollback" + 
    				" must fired on the foreground thread.");
    	}
        synchronized (listeners) {
            if (listeners.size() == 0) return null;            
        }
        final TransactionEvent evt = TransactionEvent.createRollbackTransactionEvent(this, message);
        synchronized (listeners) {
        	for (int i = listeners.size() - 1; i >= 0; i--) {
        		listeners.get(i).transactionRollback(evt);
        	}
        }
        return evt;
    }
    
    public void begin(String message) {
    	fireTransactionStarted(message);
    }
    
    public void commit() {
    	fireTransactionEnded();
    }
    
    protected boolean isForegroundThread() {
		try {
			return getSession().isForegroundThread();
		} catch (SessionNotFoundException e) {
			return true;
		}
	}
    
    /**
     * Calls the runInBackground method on the session this object is attached
     * to if it exists. If this object is not attached to a session, which can
     * occur when loading, copying, or creating a new object, the runner will be
     * run on the current thread due to not being able to run elsewhere. Any
     * SPObject that wants to run a runnable in the background should call to
     * this method instead of to the session.
     * 
     * @see WabitSession#runInBackground(Runnable)
     */
	protected void runInBackground(Runnable runner) {
	    try {
	        getSession().runInBackground(runner);
	    } catch (SessionNotFoundException e) {
	        runner.run();
	    }
	}
	
	 /**
     * Calls the runInForeground method on the session this object is attached
     * to if it exists. If this object is not attached to a session, which can
     * occur when loading, copying, or creating a new object, the runner will be
     * run on the current thread due to not being able to run elsewhere. Any
     * SPObject that wants to run a runnable in the foreground should call to
     * this method instead of to the session.
     * 
     * @see WabitSession#runInBackground(Runnable)
     */
	protected void runInForeground(Runnable runner) {
	    try {
	        getSession().runInForeground(runner);
	    } catch (SessionNotFoundException e) {
	        runner.run();
	    }
	}
    
    @Override
    public boolean equals(Object obj) {
    	return (obj instanceof SPObject && 
    			getUUID().equals(((SPObject) obj).getUUID()));
    }
    
    @Override
    public String toString() {
    	return super.toString() + ", " + getName() + ":" + getUUID();
    }

}