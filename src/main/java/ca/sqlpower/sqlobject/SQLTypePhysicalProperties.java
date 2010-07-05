/*
 * Copyright (c) 2010, SQL Power Group Inc.
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

package ca.sqlpower.sqlobject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.sql.RowSetMetaData;

import ca.sqlpower.object.SPObject;
import ca.sqlpower.object.annotation.Accessor;
import ca.sqlpower.object.annotation.Constructor;
import ca.sqlpower.object.annotation.ConstructorParameter;
import ca.sqlpower.object.annotation.Mutator;
import ca.sqlpower.object.annotation.NonProperty;
import ca.sqlpower.object.annotation.ConstructorParameter.ParameterType;
import ca.sqlpower.sql.JDBCDataSourceType;
import ca.sqlpower.sqlobject.SQLTypePhysicalPropertiesProvider.PropertyType;
import ca.sqlpower.util.SQLPowerUtils;

/**
 * A container class that stores several specific database platform-dependent
 * physical properties for a {@link JDBCSQLType} for a single specific physical platform
 */
public class SQLTypePhysicalProperties extends SQLObject {
    
    /**
     * {@link SQLTypePhysicalProperties} supports check constraints, and
     * enumeration constraints, but not both
     */
    public enum SQLTypeConstraint {
		/**
		 * This SQLType has neither check nor enumeration constraints on this
		 * platform
		 */
        NONE,

        /**
         * This SQLType has a check constraint for this platform
         */
        CHECK,

        /**
         * This SQLType has an enumeration constraint for this platform
         */
        ENUM
    }

    /**
     * The physical precision property for this type. {@link RowSetMetaData}
     * {@link #setPrecision(int)} defines it as 'the total number of decimal
     * digits'.
     */
    private Integer precision;
    
    /**
     * The physical scale property for this type. {@link RowSetMetaData}
     * {@link #setScale(int)} defines it as 'the number of digits to right of
     * decimal point'.
     */
    private Integer scale;
    
    /**
     * A String representation of the Default value for the physical type. Note
     * that the proper String representation will be dependent on the type and
     * physical platform.
     */
    private String defaultValue;

    /**
     * Indicates which constraint type applies to this physical type.
     */
    private SQLTypeConstraint constraintType;
    
    /**
     * Check constraint on this SQLType. Note that you cannot use both this AND
     * the checkConstraint
     */
    private String checkConstraint;

    /**
     * Enumeration constraint. It is a list Strings representing values that the
     * type is constrained to. Note that you cannot use both this AND the
     * checkConstraint.
     */
    private String[] enumeration;

    /**
     * The logical name of the physical database platform.
     * 
     * This would be the same value that you would get if you called
     * {@link JDBCDataSourceType#getName()} on the {@link JDBCDataSourceType}
     * representing the physical platform of this type.
     */
    private String platform = "";
    
    /**
     * The {@link PropertyType} of the precision. It determines if it is meant to
	 * be variable ({@link PropertyType#VARIABLE}), have a constant value (
	 * {@link PropertyType#CONSTANT}), or it doesn't apply to this type (
	 * {@link PropertyType#NOT_APPLICABLE}).
     */
    private PropertyType precisionType;
    
	/**
	 * The {@link PropertyType} value for scale. It determines if it is meant to
	 * be variable ({@link PropertyType#VARIABLE}), have a constant value (
	 * {@link PropertyType#CONSTANT}), or it doesn't apply to this type (
	 * {@link PropertyType#NOT_APPLICABLE}).
	 */
    private PropertyType scaleType;
    
	/**
	 * List of allowed child types, which is empty since
	 * {@link SQLTypePhysicalProperties} has no children
	 */
	public static final List<Class<? extends SPObject>> allowedChildTypes = Collections.<Class<? extends SPObject>>singletonList(SQLCheckConstraint.class);

    @Constructor
    public SQLTypePhysicalProperties(@ConstructorParameter(isProperty=ParameterType.PROPERTY, propertyName="platform") String platformName) {
    	this.platform = platformName;
    	setName("SQLTypePhysicalProperties for " + platform);
    }
    
	/**
	 * Returns the precision property as an Integer.
	 * 
	 * @return An Integer instance representing the precision property value. If it
	 *         returns null, then the precision has not been set for this
	 *         {@link SQLTypePhysicalProperties} instance.
	 */
    @Accessor
    public Integer getPrecision() {
        return precision;
    }

    @Mutator
    public void setPrecision(Integer precision) {
    	Integer oldValue = this.precision;
        this.precision = precision;
        firePropertyChange("precision", (Integer) oldValue, (Integer) precision);
    }

	/**
	 * Returns the scale property as an Integer.
	 * 
	 * @return An Integer instance representing the scale property value. If it
	 *         returns null, then the scale has not been set for this
	 *         {@link SQLTypePhysicalProperties} instance.
	 */
    @Accessor
    public Integer getScale() {
        return scale;
    }

    @Mutator
    public void setScale(Integer scale) {
    	Integer oldValue = this.scale;
        this.scale = scale;
        firePropertyChange("scale", (Integer) oldValue, (Integer) scale);
    }

    @Accessor
    public String getDefaultValue() {
        return defaultValue;
    }

    @Mutator
    public void setDefaultValue(String defaultValue) {
    	String oldValue = this.defaultValue;
        this.defaultValue = defaultValue;
        firePropertyChange("defaultValue", oldValue, defaultValue);
    }

    @Accessor
    public String getCheckConstraint() {
        return checkConstraint;
    }

    @Mutator
    public void setCheckConstraint(String checkConstraint) {
    	String oldValue = this.checkConstraint;
        this.checkConstraint = checkConstraint;
        firePropertyChange("checkConstraint", oldValue, checkConstraint);
    }

    @Accessor
    public String[] getEnumeration() {
        return enumeration;
    }

    @Mutator
    public void setEnumeration(String[] enumeration) {
    	String[] oldValue = this.enumeration;
        this.enumeration = enumeration;
        firePropertyChange("enumeration", oldValue, enumeration);
    }

    @Accessor
    public SQLTypeConstraint getConstraintType() {
    	return constraintType;
    }

    @Mutator
    public void setConstraintType(SQLTypeConstraint constraint) {
    	SQLTypeConstraint oldValue = this.constraintType;
        this.constraintType = constraint;
        firePropertyChange("constraintType", oldValue, constraintType);
    }

    @Override
    public boolean allowsChildren() {
        return false;
    }

    @Override
    public List<? extends SQLObject> getChildrenWithoutPopulating() {
        return Collections.emptyList();
    }

    @Override
    public String getShortDisplayName() {
        return getName();
    }

    @Override
    protected void populateImpl() throws SQLObjectException {
        // no children, so no-op
    }

    @Override
    protected boolean removeChildImpl(SPObject child) {
        // allowed child types is none, so removeChild should throw exception
        return false;
    }

    public int childPositionOffset(Class<? extends SPObject> childType) {
        return 0;
    }

    @NonProperty
    public List<Class<? extends SPObject>> getAllowedChildTypes() {
        return allowedChildTypes;
    }

    public List<? extends SPObject> getDependencies() {
        return Collections.emptyList();
    }

    public void removeDependency(SPObject dependency) {
        // No dependencies, so no-op
    }

    @Accessor
    public String getPlatform() {
        return platform;
    }
    
    @Accessor
    public PropertyType getPrecisionType() {
        return precisionType;
    }

    @Mutator
    public void setPrecisionType(PropertyType precisionType) {
    	PropertyType oldValue = this.precisionType;
    	this.precisionType = precisionType;
    	firePropertyChange("precisionType", oldValue, precisionType);
    }

    @Accessor
    public PropertyType getScaleType() {
        return scaleType;
    }
    
    @Mutator
    public void setScaleType(PropertyType scaleType) {
    	PropertyType oldValue = this.scaleType;
		this.scaleType = scaleType;
		firePropertyChange("scaleType", oldValue, scaleType);
	}
    
	/**
	 * Updates the properties of this properties object to match the parameter.
	 * Does not change this properties platform or parent.
	 * 
	 * @param matchMe
	 *            the SQLTypePhysicalProperties to match
	 */
    @Override
    public void updateToMatch(SQLObject matchMe) {
    	if (!(matchMe instanceof SQLTypePhysicalProperties)) {
    		throw new ClassCastException();
    	}
    	
    	SQLTypePhysicalProperties propsToMatch = (SQLTypePhysicalProperties) matchMe;

    	if (!areEqual(this, propsToMatch)) {
    		begin("Matching properties");
    		setName(propsToMatch.getName());
    		setPhysicalName(propsToMatch.getPhysicalName());
    		setPrecision(propsToMatch.getPrecision());
    		setPrecisionType(propsToMatch.getPrecisionType());
    		setScale(propsToMatch.getScale());
    		setScaleType(propsToMatch.getScaleType());
    		setEnumeration(propsToMatch.getEnumeration());
    		setDefaultValue(propsToMatch.getDefaultValue());
    		setCheckConstraint(propsToMatch.getCheckConstraint());
    		setConstraintType(propsToMatch.getConstraintType());
    		commit();
    	}
    }

	/**
	 * Compares two {@link SQLTypePhysicalProperties} objects to see if they are
	 * equal in all properties (except UUID).
	 * 
	 * @param prop1
	 *            The first of two {@link SQLTypePhysicalProperties} objects to
	 *            compare.
	 * @param prop2
	 *            The second of two {@link SQLTypePhysicalProperties} objects to
	 *            compare.
	 * @return true iff the two {@link SQLTypePhysicalProperties} objects are
	 *         equal.
	 */
    public static boolean areEqual(SQLTypePhysicalProperties prop1, SQLTypePhysicalProperties prop2) {
		return SQLPowerUtils.areEqual(prop1.getName(), prop2.getName())
				&& SQLPowerUtils.areEqual(prop1.getPhysicalName(), prop2.getPhysicalName())
				&& SQLPowerUtils.areEqual(prop1.getPrecision(), prop2.getPrecision())
				&& SQLPowerUtils.areEqual(prop1.getPrecisionType(), prop2.getPrecisionType())
				&& SQLPowerUtils.areEqual(prop1.getScale(), prop2.getScale())
				&& SQLPowerUtils.areEqual(prop1.getScaleType(), prop2.getScaleType())
				&& Arrays.equals(prop1.getEnumeration(), prop2.getEnumeration())
				&& SQLPowerUtils.areEqual(prop1.getDefaultValue(), prop2.getDefaultValue())
				&& SQLPowerUtils.areEqual(prop1.getCheckConstraint(), prop2.getCheckConstraint())
				&& SQLPowerUtils.areEqual(prop1.getConstraintType(), prop2.getConstraintType());
    }
}

