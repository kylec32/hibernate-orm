/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2008, Red Hat Middleware LLC or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Middleware LLC.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 *
 */
package org.luceehibernate.persister.entity;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;

import org.luceehibernate.AssertionFailure;
import org.luceehibernate.Hibernate;
import org.luceehibernate.HibernateException;
import org.luceehibernate.LockMode;
import org.luceehibernate.MappingException;
import org.luceehibernate.cache.access.EntityRegionAccessStrategy;
import org.luceehibernate.cfg.Settings;
import org.luceehibernate.dialect.Dialect;
import org.luceehibernate.engine.Mapping;
import org.luceehibernate.engine.SessionFactoryImplementor;
import org.luceehibernate.engine.ExecuteUpdateResultCheckStyle;
import org.luceehibernate.id.IdentityGenerator;
import org.luceehibernate.mapping.Column;
import org.luceehibernate.mapping.PersistentClass;
import org.luceehibernate.mapping.Subclass;
import org.luceehibernate.mapping.Table;
import org.luceehibernate.sql.SelectFragment;
import org.luceehibernate.sql.SimpleSelect;
import org.luceehibernate.type.Type;
import org.luceehibernate.util.ArrayHelper;
import org.luceehibernate.util.JoinedIterator;
import org.luceehibernate.util.SingletonIterator;

/**
 * Implementation of the "table-per-concrete-class" or "roll-down" mapping 
 * strategy for an entity and its inheritence hierarchy.
 *
 * @author Gavin King
 */
public class UnionSubclassEntityPersister extends AbstractEntityPersister {

	// the class hierarchy structure
	private final String subquery;
	private final String tableName;
	//private final String rootTableName;
	private final String[] subclassClosure;
	private final String[] spaces;
	private final String[] subclassSpaces;
	private final String discriminatorSQLValue;
	private final Map subclassByDiscriminatorValue = new HashMap();

	private final String[] constraintOrderedTableNames;
	private final String[][] constraintOrderedKeyColumnNames;

	//INITIALIZATION:

	public UnionSubclassEntityPersister(
			final PersistentClass persistentClass, 
			final EntityRegionAccessStrategy cacheAccessStrategy,
			final SessionFactoryImplementor factory,
			final Mapping mapping) throws HibernateException {

		super( persistentClass, cacheAccessStrategy, factory );
		
		if ( getIdentifierGenerator() instanceof IdentityGenerator ) {
			throw new MappingException(
					"Cannot use identity column key generation with <union-subclass> mapping for: " + 
					getEntityName() 
			);
		}

		// TABLE

		tableName = persistentClass.getTable().getQualifiedName( 
				factory.getDialect(), 
				factory.getSettings().getDefaultCatalogName(), 
				factory.getSettings().getDefaultSchemaName() 
		);
		/*rootTableName = persistentClass.getRootTable().getQualifiedName( 
				factory.getDialect(), 
				factory.getDefaultCatalog(), 
				factory.getDefaultSchema() 
		);*/

		//Custom SQL

		String sql;
		boolean callable = false;
		ExecuteUpdateResultCheckStyle checkStyle = null;
		sql = persistentClass.getCustomSQLInsert();
		callable = sql != null && persistentClass.isCustomInsertCallable();
		checkStyle = sql == null
				? ExecuteUpdateResultCheckStyle.COUNT
	            : persistentClass.getCustomSQLInsertCheckStyle() == null
						? ExecuteUpdateResultCheckStyle.determineDefault( sql, callable )
	                    : persistentClass.getCustomSQLInsertCheckStyle();
		customSQLInsert = new String[] { sql };
		insertCallable = new boolean[] { callable };
		insertResultCheckStyles = new ExecuteUpdateResultCheckStyle[] { checkStyle };

		sql = persistentClass.getCustomSQLUpdate();
		callable = sql != null && persistentClass.isCustomUpdateCallable();
		checkStyle = sql == null
				? ExecuteUpdateResultCheckStyle.COUNT
	            : persistentClass.getCustomSQLUpdateCheckStyle() == null
						? ExecuteUpdateResultCheckStyle.determineDefault( sql, callable )
	                    : persistentClass.getCustomSQLUpdateCheckStyle();
		customSQLUpdate = new String[] { sql };
		updateCallable = new boolean[] { callable };
		updateResultCheckStyles = new ExecuteUpdateResultCheckStyle[] { checkStyle };

		sql = persistentClass.getCustomSQLDelete();
		callable = sql != null && persistentClass.isCustomDeleteCallable();
		checkStyle = sql == null
				? ExecuteUpdateResultCheckStyle.COUNT
	            : persistentClass.getCustomSQLDeleteCheckStyle() == null
						? ExecuteUpdateResultCheckStyle.determineDefault( sql, callable )
	                    : persistentClass.getCustomSQLDeleteCheckStyle();
		customSQLDelete = new String[] { sql };
		deleteCallable = new boolean[] { callable };
		deleteResultCheckStyles = new ExecuteUpdateResultCheckStyle[] { checkStyle };

		discriminatorSQLValue = String.valueOf( persistentClass.getSubclassId() );

		// PROPERTIES

		int subclassSpan = persistentClass.getSubclassSpan() + 1;
		subclassClosure = new String[subclassSpan];
		subclassClosure[0] = getEntityName();

		// SUBCLASSES
		subclassByDiscriminatorValue.put( 
				new Integer( persistentClass.getSubclassId() ), 
				persistentClass.getEntityName() 
		);
		if ( persistentClass.isPolymorphic() ) {
			Iterator iter = persistentClass.getSubclassIterator();
			int k=1;
			while ( iter.hasNext() ) {
				Subclass sc = (Subclass) iter.next();
				subclassClosure[k++] = sc.getEntityName();
				subclassByDiscriminatorValue.put( new Integer( sc.getSubclassId() ), sc.getEntityName() );
			}
		}
		
		//SPACES
		//TODO: i'm not sure, but perhaps we should exclude
		//      abstract denormalized tables?
		
		int spacesSize = 1 + persistentClass.getSynchronizedTables().size();
		spaces = new String[spacesSize];
		spaces[0] = tableName;
		Iterator iter = persistentClass.getSynchronizedTables().iterator();
		for ( int i=1; i<spacesSize; i++ ) {
			spaces[i] = (String) iter.next();
		}
		
		HashSet subclassTables = new HashSet();
		iter = persistentClass.getSubclassTableClosureIterator();
		while ( iter.hasNext() ) {
			Table table = (Table) iter.next();
			subclassTables.add( table.getQualifiedName(
					factory.getDialect(), 
					factory.getSettings().getDefaultCatalogName(), 
					factory.getSettings().getDefaultSchemaName() 
			) );
		}
		subclassSpaces = ArrayHelper.toStringArray(subclassTables);

		subquery = generateSubquery(persistentClass, mapping);

		if ( isMultiTable() ) {
			int idColumnSpan = getIdentifierColumnSpan();
			ArrayList tableNames = new ArrayList();
			ArrayList keyColumns = new ArrayList();
			if ( !isAbstract() ) {
				tableNames.add( tableName );
				keyColumns.add( getIdentifierColumnNames() );
			}
			iter = persistentClass.getSubclassTableClosureIterator();
			while ( iter.hasNext() ) {
				Table tab = ( Table ) iter.next();
				if ( !tab.isAbstractUnionTable() ) {
					String tableName = tab.getQualifiedName(
							factory.getDialect(),
							factory.getSettings().getDefaultCatalogName(),
							factory.getSettings().getDefaultSchemaName()
					);
					tableNames.add( tableName );
					String[] key = new String[idColumnSpan];
					Iterator citer = tab.getPrimaryKey().getColumnIterator();
					for ( int k=0; k<idColumnSpan; k++ ) {
						key[k] = ( ( Column ) citer.next() ).getQuotedName( factory.getDialect() );
					}
					keyColumns.add( key );
				}
			}

			constraintOrderedTableNames = ArrayHelper.toStringArray( tableNames );
			constraintOrderedKeyColumnNames = ArrayHelper.to2DStringArray( keyColumns );
		}
		else {
			constraintOrderedTableNames = new String[] { tableName };
			constraintOrderedKeyColumnNames = new String[][] { getIdentifierColumnNames() };
		}

		initLockers();

		initSubclassPropertyAliasesMap(persistentClass);
		
		postConstruct(mapping);

	}

	public Serializable[] getQuerySpaces() {
		return subclassSpaces;
	}
	
	public String getTableName() {
		return subquery;
	}

	public Type getDiscriminatorType() {
		return Hibernate.INTEGER;
	}

	public String getDiscriminatorSQLValue() {
		return discriminatorSQLValue;
	}

	public String[] getSubclassClosure() {
		return subclassClosure;
	}

	public String getSubclassForDiscriminatorValue(Object value) {
		return (String) subclassByDiscriminatorValue.get(value);
	}

	public Serializable[] getPropertySpaces() {
		return spaces;
	}

	protected boolean isDiscriminatorFormula() {
		return false;
	}

	/**
	 * Generate the SQL that selects a row by id
	 */
	protected String generateSelectString(LockMode lockMode) {
		SimpleSelect select = new SimpleSelect( getFactory().getDialect() )
			.setLockMode(lockMode)
			.setTableName( getTableName() )
			.addColumns( getIdentifierColumnNames() )
			.addColumns( 
					getSubclassColumnClosure(), 
					getSubclassColumnAliasClosure(),
					getSubclassColumnLazyiness()
			)
			.addColumns( 
					getSubclassFormulaClosure(), 
					getSubclassFormulaAliasClosure(),
					getSubclassFormulaLazyiness()
			);
		//TODO: include the rowids!!!!
		if ( hasSubclasses() ) {
			if ( isDiscriminatorFormula() ) {
				select.addColumn( getDiscriminatorFormula(), getDiscriminatorAlias() );
			}
			else {
				select.addColumn( getDiscriminatorColumnName(), getDiscriminatorAlias() );
			}
		}
		if ( getFactory().getSettings().isCommentsEnabled() ) {
			select.setComment( "load " + getEntityName() );
		}
		return select.addCondition( getIdentifierColumnNames(), "=?" ).toStatementString();
	}

	protected String getDiscriminatorFormula() {
		return null;
	}

	protected String getTableName(int j) {
		return tableName;
	}

	protected String[] getKeyColumns(int j) {
		return getIdentifierColumnNames();
	}
	
	protected boolean isTableCascadeDeleteEnabled(int j) {
		return false;
	}
	
	protected boolean isPropertyOfTable(int property, int j) {
		return true;
	}

	// Execute the SQL:

	public String fromTableFragment(String name) {
		return getTableName() + ' '  + name;
	}

	public String filterFragment(String name) {
		return hasWhere() ?
			" and " + getSQLWhereString(name) :
			"";
	}

	public String getSubclassPropertyTableName(int i) {
		return getTableName();//ie. the subquery! yuck!
	}

	protected void addDiscriminatorToSelect(SelectFragment select, String name, String suffix) {
		select.addColumn( name, getDiscriminatorColumnName(),  getDiscriminatorAlias() );
	}
	
	protected int[] getPropertyTableNumbersInSelect() {
		return new int[ getPropertySpan() ];
	}

	protected int getSubclassPropertyTableNumber(int i) {
		return 0;
	}

	public int getSubclassPropertyTableNumber(String propertyName) {
		return 0;
	}

	public boolean isMultiTable() {
		// This could also just be true all the time...
		return isAbstract() || hasSubclasses();
	}

	public int getTableSpan() {
		return 1;
	}

	protected int[] getSubclassColumnTableNumberClosure() {
		return new int[ getSubclassColumnClosure().length ];
	}

	protected int[] getSubclassFormulaTableNumberClosure() {
		return new int[ getSubclassFormulaClosure().length ];
	}

	protected boolean[] getTableHasColumns() {
		return new boolean[] { true };
	}

	protected int[] getPropertyTableNumbers() {
		return new int[ getPropertySpan() ];
	}

	protected String generateSubquery(PersistentClass model, Mapping mapping) {

		Dialect dialect = getFactory().getDialect();
		Settings settings = getFactory().getSettings();
		
		if ( !model.hasSubclasses() ) {
			return model.getTable().getQualifiedName(
					dialect,
					settings.getDefaultCatalogName(),
					settings.getDefaultSchemaName()
				);
		}

		HashSet columns = new LinkedHashSet();
		Iterator titer = model.getSubclassTableClosureIterator();
		while ( titer.hasNext() ) {
			Table table = (Table) titer.next();
			if ( !table.isAbstractUnionTable() ) {
				Iterator citer = table.getColumnIterator();
				while ( citer.hasNext() ) columns.add( citer.next() );
			}
		}

		StringBuffer buf = new StringBuffer()
			.append("( ");

		Iterator siter = new JoinedIterator(
			new SingletonIterator(model),
			model.getSubclassIterator()
		);

		while ( siter.hasNext() ) {
			PersistentClass clazz = (PersistentClass) siter.next();
			Table table = clazz.getTable();
			if ( !table.isAbstractUnionTable() ) {
				//TODO: move to .sql package!!
				buf.append("select ");
				Iterator citer = columns.iterator();
				while ( citer.hasNext() ) {
					Column col = (Column) citer.next();
					if ( !table.containsColumn(col) ) {
						int sqlType = col.getSqlTypeCode(mapping);
						buf.append( dialect.getSelectClauseNullString(sqlType) )
							.append(" as ");
					}
					buf.append( col.getQuotedName( dialect ) );
					buf.append(", ");
				}
				buf.append( clazz.getSubclassId() )
					.append(" as clazz_");
				buf.append(" from ")
					.append( table.getQualifiedName(
							dialect,
							settings.getDefaultCatalogName(),
							settings.getDefaultSchemaName()
					) );
				buf.append(" union ");
				if ( dialect.supportsUnionAll() ) {
					buf.append("all ");
				}
			}
		}
		
		if ( buf.length() > 2 ) {
			//chop the last union (all)
			buf.setLength( buf.length() - ( dialect.supportsUnionAll() ? 11 : 7 ) );
		}

		return buf.append(" )").toString();
	}

	protected String[] getSubclassTableKeyColumns(int j) {
		if (j!=0) throw new AssertionFailure("only one table");
		return getIdentifierColumnNames();
	}

	public String getSubclassTableName(int j) {
		if (j!=0) throw new AssertionFailure("only one table");
		return tableName;
	}

	public int getSubclassTableSpan() {
		return 1;
	}

	protected boolean isClassOrSuperclassTable(int j) {
		if (j!=0) throw new AssertionFailure("only one table");
		return true;
	}

	public String getPropertyTableName(String propertyName) {
		//TODO: check this....
		return getTableName();
	}

	public String[] getConstraintOrderedTableNameClosure() {
			return constraintOrderedTableNames;
	}

	public String[][] getContraintOrderedTableKeyColumnClosure() {
		return constraintOrderedKeyColumnNames;
	}
}