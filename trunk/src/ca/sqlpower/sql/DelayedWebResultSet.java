package ca.sqlpower.sql;

import java.sql.*;
import java.util.*;
import ca.sqlpower.util.*;
import ca.sqlpower.sql.CachedRowSet;

public class DelayedWebResultSet extends WebResultSet {

	/**
	 * Holds the set of cached resultset objects (keyed on SQL query
	 * string).  Never reference this directly; use getResultCache(),
	 * which can be overridden by subclasses.  Also, never call
	 * put(key,value) directly on the cache; use
	 * addResultsToCache(key,value) because that can also be
	 * overridden.
	 */
	private static Cache resultCache = null;
	private static Object resultCacheMutex = new Object();

	protected int givenColCount;

	/**
	 * The JDBC Connection that was last passed to the execute()
	 * method, or null if execute hasn't been called yet.  This
	 * instance variable may be moved up to the WebResultSet class in
	 * the future.
	 */
	protected Connection con;

	/**
	 * Controls whether or not this instance of DelayedWebResultSet
	 * will use the cache of query results.  It is almost always best
	 * to use the cache, except:
	 * <ul>
	 *  <li>When the data rendered to this screen is expected to have changed
	 *  <li>When the expected data set is too large to cache in RAM
 	 * </ul>
	 */
	protected boolean cacheEnabled;

	/**
	 * The amount of time spent in Statement.execute() for this query.
	 * It only makes sense to check this value after calling
	 * execute().
	 */
	protected long queryExecuteTime;

	/**
	 * The amount of time spent in CachedRowSet.populate() for this
	 * query.  It only makes sense to check this value after calling
	 * execute().
	 */
	protected long resultPopulateTime;

	/**
	 * This will be true iff the results were retrieved from the
	 * result cache rather than the database.  It only makes sense to
	 * check this value after calling execute().
	 */
	protected boolean fromCache;

	/**
	 * This is the total amount of time spent in the execute method,
	 * regardless of how the results were obtained. It only makes
	 * sense to check this value after calling execute().
	 */
	protected long totalExecuteTime;

	/**
	 * Creates a new <code>DelayedWebResultSet</code> which uses the
	 * query resultset cache.
	 *
	 * @param cols The number of columns the <code>query</code> will
	 * generate when executed.
	 * @param query An SQL query statement.
	 */
	public DelayedWebResultSet(int cols, String query) {
		this(cols, query, true);
	}

	/**
	 * Creates a new <code>DelayedWebResultSet</code>.
	 *
	 * @param cols The number of columns the <code>query</code> will
	 * generate when executed.
	 * @param query An SQL query statement.
	 * @param useCache If true, this DelayedWebResultSet will try to
	 * read from or write to the cache on execute.
	 */
	public DelayedWebResultSet(int cols, String query, boolean useCache) {
		this.sqlQuery=query;
		this.givenColCount=cols;
		this.cacheEnabled=useCache;
		this.con=null;
		initMembers(cols);
	}

	/**
	 * Does nothing.  Provided for subclasses that want to use
	 * different constructor signatures.
	 */
	protected DelayedWebResultSet() {
		super();
		this.sqlQuery=null;
		this.givenColCount=0;
		this.cacheEnabled=true;
		this.con=null;		
	}

	/**
	 * Executes the query that was given in the constructor.  After
	 * this method has been called, the DelayedWebResultSet will
	 * behave exactly like a regular WebResultSet.
	 *
	 * @param con A live JDBC connection.
	 * @throws IllegalStateException if the actual number of columns
	 * generated by executing the query doesn't match the number
	 * (<code>cols</code>) given to the constructor.
	 * @throws SQLException if there is a database error (most often
	 * due to a syntax error in the SQL query).
	 */
	public void execute(Connection con) throws IllegalStateException, SQLException {
		try {
			execute(con, true);
		} catch (SQLException e) {
			System.out.println("dwrs caught sqlexception from query: "+sqlQuery);
			throw e;
		}
	}

	/**
	 * Executes the query that was given in the constructor.  After
	 * this method has been called, the DelayedWebResultSet will
	 * behave exactly like a regular WebResultSet.
	 *
	 * @param con A live JDBC connection.
	 * @param closeOldRS If this argument is <code>true</code>, any
	 * previous (and still-open) ResultSet attached to this
	 * DelayedWebResultSet will be closed before binding the new one.
	 * @throws IllegalStateException if the actual number of columns
	 * generated by executing the query doesn't match the number
	 * (<code>cols</code>) given to the constructor.
	 * @throws SQLException if there is a database error (most often
	 * due to a syntax error in the SQL query).
	 */
	protected void execute(Connection con, boolean closeOldRS)
		throws IllegalStateException, SQLException {

		long startTime = System.currentTimeMillis();
		this.con = con;
		this.fromCache = false;
		ResultSet newRS=null;

		if(cacheEnabled) {
				
			String cacheKey = sqlQuery 
				+"&"+con.getMetaData().getURL() 
				+"&"+con.getMetaData().getUserName();
			
			CachedRowSet results = (CachedRowSet) getCachedResult(cacheKey);
			if (results != null) {
				// we don't want to close cached resultset
				closeOldRS=false;
				queryExecuteTime = 0;
				resultPopulateTime = 0;
				fromCache = true;
			} else {
				long queryStartTime = System.currentTimeMillis();
				Statement stmt = null;
				try {
					stmt = con.createStatement();
					results = new CachedRowSet();
					ResultSet rs = stmt.executeQuery(sqlQuery);
					queryExecuteTime = System.currentTimeMillis() - queryStartTime;
					results.populate(rs);
					resultPopulateTime = System.currentTimeMillis() - queryStartTime - queryExecuteTime;
				} finally {
					if (stmt != null) {
						stmt.close();
					}
				}
				addResultsToCache(cacheKey, results);
			}
			newRS=results;
		} else {
			// not using cache
			if (closeOldRS && rs != null) {
				Statement stmt = rs.getStatement();
				if (stmt !=null) stmt.close();
			}
			Statement stmt = con.createStatement();
			long queryStartTime = System.currentTimeMillis();
			newRS = stmt.executeQuery(sqlQuery);
			queryExecuteTime = System.currentTimeMillis() - queryStartTime;
			resultPopulateTime = 0;
		}

		applyResultSet(newRS, closeOldRS);
		columnCountSanityCheck();

		this.totalExecuteTime = System.currentTimeMillis() - startTime;
	}

	/**
	 * The execute method calls this just before returning to make
	 * sure everything adds up (and the user didn't specify an
	 * incorrect column count).
	 *
	 * @throws IllegalStateException if the given column count differs
	 * from the actual column count generated by the SQL query.
	 */
	protected void columnCountSanityCheck() throws SQLException, IllegalStateException {
		if (rsmd.getColumnCount() != givenColCount) {
			throw new IllegalStateException(
				"The SQL query returned "
					+ rsmd.getColumnCount()
					+ " columns, but the number of columns originally specified was "
					+ givenColCount
					+ ".");
		}
	}

	/**
	 * Just gives back the column count specified in the constructor.
	 *
	 * @return The number of columns that this DelayedWebResultSet has.
	 */
	public int getColumnCount() {
		return givenColCount;
	}

	/**
	 * Returns true if this DelayedWebResultSet is using the result
	 * set cache for query execution.
	 */
	public boolean isCacheEnabled() {
		return cacheEnabled;
	}

	/**
	 * Controls whether this DelayedWebResultSet is using the result
	 * set cache for query execution.  You can't change this value
	 * after calling {@link #execute(Connection)}.
	 *
	 * @param v <code>True</code> if you want the result set to use
	 * the cache; <code>false</code> if you want fresh data directly
	 * from the database.
	 * @throws IllegalStateException if called after <code>execute()</code>.
	 */
	public void setCacheEnabled(boolean v) {
		cacheEnabled=v;
	}
	
	/**
	 * Returns the cache that the DelayedWebResultSets in this JVM are
	 * using.  You should always use this method for getting the
	 * cache; it can be overridden by subclasses so it might not
	 * reference the private static resultCache variable.
	 */
	public Cache getResultCache() {
		if (resultCache == null) {
			synchronized (resultCacheMutex) {
				if (resultCache == null) {
					resultCache = new SynchronizedCache(new LeastRecentlyUsedCache(100));
				}
			}
		}
		return resultCache;
	}
	
	/**
	 * Exists mainly as a backdoor for the CacheStatsServlet.
	 */
	public static Cache staticGetResultCache() {
		if (resultCache == null) {
			synchronized (resultCacheMutex) {
				if (resultCache == null) {
					resultCache = new SynchronizedCache(new LeastRecentlyUsedCache(100));
				}
			}
		}
		return resultCache;
	}

	/**
	 * This method adds the given results to the cache under the given
	 * key.  It exists primarily as a hook for subclasses to use
	 * fancier caches: if you override this, you can use custom put()
	 * methods on your cache.
	 *
	 * @param key The value that will be given to getCachedResult when
	 * and if this row set needs to be retrieved again.
	 * @param results The CachedRowSet to add to the cache.
	 */
	protected void addResultsToCache(String key, CachedRowSet results) throws SQLException  {
		getResultCache().put(key, results);
	}

	/**
	 * Retrieves a result from the result cache.  Override this if you
	 * need to do anything special to the result set after it's
	 * retrieved (reset the cursor, make a copy, sort it, filter it,
	 * etc).
	 *
	 * @param key The cache key.  For a given result set, this will be
	 * the same key that was passed to
	 * {@link #addResultsToCache(String,CachedRowSet)}.
	 * @return A copy of the CachedRowSet that was previously stored
	 * under the same key, or null if there was nothing stored in the
	 * cache under that key.
	 */
	protected CachedRowSet getCachedResult(String key) throws SQLException {
		CachedRowSet results = (CachedRowSet) getResultCache().get(key);
		if (results != null) {
			results = results.createShared();
			// reset cursor, which is likely afterLast right now
			results.beforeFirst();
		}
		return results;
	}

	/**
	 * Behaves like close() in WebResultSet unless the
	 * DelayedWebResultSet result cache is turned on.  In that case,
	 * does nothing because the database resources are already released.
	 */
	public void close() throws SQLException {
		if(!cacheEnabled) {
			super.close();
		}
	}

	public boolean isEmpty() throws SQLException {
		if(! (rs instanceof CachedRowSet) ) {
			throw new UnsupportedOperationException
				("Can't tell if result set is empty unless caching is enabled");
		} else {
			return ((CachedRowSet) rs).size() == 0;
		}
	}

	/**
	 * See @link{#queryExecuteTime}.
	 */
	public long getQueryExecuteTime() {
		return queryExecuteTime;
	}
	
	/**
	 * See @link{#resultPopulateTime}.
	 */
	public long getResultPopulateTime() {
		return resultPopulateTime;
	}
	
	/**
	 * See @link{#fromCache}.
	 */
	public boolean isFromCache() {
		return fromCache;
	}
	
	/**
	 * See @link{#totalExecuteTime}.
	 */
	public long getTotalExecuteTime() {
		return totalExecuteTime;
	}
}
