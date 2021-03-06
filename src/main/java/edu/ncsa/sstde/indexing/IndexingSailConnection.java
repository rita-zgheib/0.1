/*
 * Copyright 2010 by TalkingTrends (Amsterdam, The Netherlands)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://opensahara.com/licenses/apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package edu.ncsa.sstde.indexing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

import info.aduna.iteration.CloseableIteration;

import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.query.BindingSet;
import org.openrdf.query.Dataset;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.algebra.BinaryTupleOperator;
import org.openrdf.query.algebra.BinaryValueOperator;
import org.openrdf.query.algebra.Compare;
import org.openrdf.query.algebra.FunctionCall;
import org.openrdf.query.algebra.Or;
import org.openrdf.query.algebra.Order;
import org.openrdf.query.algebra.OrderElem;
import org.openrdf.query.algebra.QueryModelNode;
import org.openrdf.query.algebra.QueryRoot;
import org.openrdf.query.algebra.Reduced;
import org.openrdf.query.algebra.Regex;
import org.openrdf.query.algebra.SameTerm;
import org.openrdf.query.algebra.Slice;
import org.openrdf.query.algebra.StatementPattern;
import org.openrdf.query.algebra.TupleExpr;
import org.openrdf.query.algebra.ValueConstant;
import org.openrdf.query.algebra.ValueExpr;
import org.openrdf.query.algebra.Var;
import org.openrdf.query.algebra.evaluation.impl.BindingAssigner;
import org.openrdf.query.algebra.helpers.QueryModelVisitorBase;
import org.openrdf.sail.Sail;
import org.openrdf.sail.SailConnection;
import org.openrdf.sail.SailException;
import org.openrdf.sail.helpers.SailConnectionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.useekm.indexing.exception.IndexException;
import com.useekm.indexing.internal.Indexer;
import com.useekm.indexing.internal.QueryEvaluator;

import edu.ncsa.sstde.indexing.GraphAnalyzer.MatchedIndexedGraph;
import edu.ncsa.sstde.indexing.algebra.IndexerExpr;

/**
 * Provides the connection to an {@link IndexingSail}.
 * <p>
 * Statements added to an IndexingSailConnection will (based on the
 * {@link IndexerSettings}) be added to the underlying indexer. The
 * IndexingSailConnection will rewrite queries to use the managed index where
 * appropriate.
 * <p>
 * The method {@link #evaluate(TupleExpr, Dataset, BindingSet, boolean)}
 * rewrites SPARQL and SeRQL queries to a combination of queries to the
 * {@link Sail} and the index.
 * 
 * @see IndexingSail
 * @see IndexerSettings
 */

public class IndexingSailConnection extends SailConnectionWrapper {

	// private static final Logger LOG =
	// LoggerFactory.getLogger(IndexingSailConnection.class);
	private static final Logger LOG = LoggerFactory
			.getLogger(IndexingSailConnection.class);
	static final String COMMIT_NOT_ALLOWED = "Trying to commit after an exception has been raised.";
	// private final Indexer indexer;
	private final IndexManager indexManager; 
	private static final int CACHE_SIZE = 1000;
	private final ValueFactory valueFactory;
	private final QueryEvaluator queryEvaluator;
	private HashSet<Statement> toAdd = new HashSet<Statement>();
	private HashSet<Statement> toRemove = new HashSet<Statement>();

	/**
	 * It contains the components for a statement: subject, predicate, object
	 * and context. It is used to cache the data reading/writing for later use.
	 * The reason I created it instead of using "StatementImpl" is that the
	 * latter does not provide a interface to set the "Context"
	 * 
	 * @author liangyu
	 * 
	 */
	public class StatementWrapper implements Statement {
		/**
		 * 
		 */
		private static final long serialVersionUID = 1L;
		private Resource subject = null;
		private URI predicate = null;
		private Value object = null;
		private Resource context = null;

		public Resource getSubject() {
			return subject;
		}

		public URI getPredicate() {
			return predicate;
		}

		public Value getObject() {
			return object;
		}

		public Resource getContext() {
			return context;
		}

		public StatementWrapper(Resource subj, URI pred, Value obj, Resource ctx) {
			this.subject = subj;
			this.predicate = pred;
			this.object = obj;
			this.context = ctx;
		}
	}

	/**
	 * The construction method has been changed to use the "IndexManager"
	 * instead of a "Indexer"
	 * 
	 * @param wrappedConnection
	 * @param manager
	 * @param valueFactory
	 * @param queryEvaluator
	 */
	public IndexingSailConnection(SailConnection wrappedConnection,
			IndexManager manager, ValueFactory valueFactory,
			QueryEvaluator queryEvaluator) {
		super(wrappedConnection);
		// this.indexer = manager.createIndexer();
		this.indexManager = manager;
		this.valueFactory = valueFactory;
		this.queryEvaluator = queryEvaluator;
	}

	public QueryEvaluator getQueryEvaluator() {
		return queryEvaluator;
	}

	public IndexManager getIndexManager() {
		return this.indexManager;
	}

	public ValueFactory getValueFactory() {
		return valueFactory;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Adds the statement to the {@link Sail}, and indexes the statement with
	 * the external {@link Indexer} if configured to do so.
	 * <p>
	 * 
	 * @throws SailException
	 *             When addition of the statement to the {@link Sail} or to the
	 *             {@link Indexer} fails. When a call to addStatement fails, the
	 *             transaction should be rolled back. If you try to commit after
	 *             a {@link SailException} has been thrown, the commit will
	 *             fail.
	 * @throws IllegalStateException
	 *             If the connection has been closed.
	 */
	@Override
	public synchronized void addStatement(Resource subj, URI pred, Value obj,
			Resource... ctx) throws SailException {

		// when adding a statement, I do not do anything but cache the
		// statements and later do the reading/writing in the "commit" method.
		// The reason I do this is, the indexer needs to query the sail to get
		// all the sub-graphs, which is not doable befor the sailconnection
		// commits the changes.
		Statement statement = new StatementWrapper(subj, pred, obj, null);

		// to be safe, if the same statement is in the "toRemove" list, it
		// should be removed first
		if (this.toRemove.contains(statement)) {
			this.toRemove.remove(statement);
		}
		this.toAdd.add(new StatementWrapper(subj, pred, obj, null));
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Removes the statement from the {@link Sail}, and from the external
	 * {@link Indexer} if configured to do so.
	 * <p>
	 * 
	 * @throws SailException
	 *             When statement removal in the {@link Sail} or to the
	 *             {@link Indexer} fails. When a call to addStatement fails, the
	 *             transaction should be rolled back. If you try to commit after
	 *             a {@link SailException} has been thrown, the commit will
	 *             fail.
	 * @throws IllegalStateException
	 *             If the connection has been closed.
	 */
	@Override
	public synchronized void removeStatements(Resource subj, URI pred,
			Value obj, Resource... ctx) throws SailException {

		// first to query all the statements by searching
		CloseableIteration<? extends Statement, SailException> statements = super
				.getStatements(subj, pred, obj, false);

		int cacheCount = 0;
		for (int i = 1; statements.hasNext(); i++) {
			Statement statement = statements.next();
			removeStatement(statement.getSubject(), statement.getPredicate(),
					statement.getObject(), statement.getContext());

			// the statements to remove could be any number of triples depending
			// on the input parameters, so I need commit once the number reach a
			// certain value, otherwise it will be out of memory
			if (++cacheCount >= CACHE_SIZE) {
				cacheCount = 0;
				commit();
				LOG.info("deleted " + i + " statements");

			}
		}

		commit();

	}

	/**
	 * This is to remove a concrete statement, which means the subject,
	 * predicate, and object are all not-null
	 * 
	 * @param subj
	 * @param pred
	 * @param obj
	 * @param ctx
	 * @throws SailException
	 */
	public synchronized void removeStatement(Resource subj, URI pred,
			Value obj, Resource ctx) throws SailException {
		// add the statement to the to-remove list
		Statement statement = new StatementWrapper(subj, pred, obj, ctx);
		// to be safe, we should search if this statement already exists in the
		// to-Add list
		if (this.toAdd.contains(statement)) {
			this.toAdd.remove(statement);
		}
		this.toRemove.add(statement);
	}

	/**
	 * 
	 * 
	 * @throws UnsupportedOperationException
	 */
	@Override
	public synchronized void clear(Resource... ctx) throws SailException {
		super.clear();
		this.getIndexManager().clear();

	}

	/**
	 * {@inheritDoc} This is the place where all the reading/writing actually
	 * happen
	 * 
	 * @throws SailException
	 *             When the commit on the underlying {@link Sail} or
	 *             {@link Indexer} fails. In that case a {@link #rollback()} or
	 *             {@link #close()} is required. <strong>Note that there is no
	 *             proper two-phase commit. A commit may partially succeed,
	 *             leaving {@link Sail} and {@link Indexer} in an inconsistent
	 *             state. See <a
	 *             href="https://dev.opensahara.com/issues/10">#10</a></strong>
	 */
	@Override
	public void commit() throws SailException {

		// We should commit the writing to RDF repository first so that we can
		// query it to get all the patterns to be indexed
		for (Statement statement : this.toAdd) {
			super.addStatement(statement.getSubject(),
					statement.getPredicate(), statement.getObject(),
					statement.getContext());

			// System.out.println(i++);
		}
		super.commit();
		this.indexManager.addBatch(this.getWrappedConnection(), this.toAdd);

		// in an inverse way, we should first query the RDF repository to remove
		// all the index items before we commit the removing to RDF repository.
		this.indexManager.removeBatch(this.getWrappedConnection(),
				this.toRemove);

		for (Statement statement : this.toRemove) {
			super.removeStatements(statement.getSubject(),
					statement.getPredicate(), statement.getObject(),
					statement.getContext());
		}
		super.commit();
		if (this.toAdd.size() > 0) {
			LOG.info(String.valueOf(this.toAdd.size())
					+ " triples have been loaded.");
		}
		if (this.toRemove.size() > 0) {
			LOG.info(String.valueOf(this.toRemove.size())
					+ " triples have been removed.");
		}

		this.toAdd.clear();
		this.toRemove.clear();

	}

	/**
	 * {@inheritDoc} Because the data are not written to any repository before
	 * commit, the rollback only simply clear the cache lists
	 */
	@Override
	public void rollback() throws SailException {
		this.toAdd.clear();
		this.toRemove.clear();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void close() throws SailException {
		super.close();
		// try {
		// indexManager.close();
		// } catch (IndexException e) {
		// throw new SailException(e);
		// } finally {
		// super.close();
		// }
	}

	public void removeObjectDesc(Reduced reduced){
		reduced.visit(new QueryModelVisitorBase<RuntimeException>(){

			@Override
			public void meet(SameTerm node) throws RuntimeException {
				if (node.getLeftArg() instanceof Var) {
					if(((Var)node.getLeftArg()).getName().equals("-descr-obj")){
						if (node.getParentNode() instanceof Or) {
							Or parent = (Or) node.getParentNode();
							if (parent.getLeftArg() == node) {
								parent.replaceWith(parent.getRightArg());
							}else if (parent.getRightArg() == node){
								parent.replaceWith(parent.getLeftArg());
							}
							
						}
						
					}
				}
			}
			
		});
	}
	
	
	/**
	 * {@inheritDoc}
	 * <p>
	 * Evaluates the provided {@link TupleExpr} by splitting the query in parts
	 * that should be executed by the {@link Indexer}, and parts that use the
	 * wrapped {@link SailConnection}. The {@link TupleExpr} will be rewritten
	 * to combine the results of {@link Indexer} and wrapped
	 * {@link SailConnection}.
	 * 
	 * @see IndexerExpr
	 */
	@Override
	public CloseableIteration<? extends BindingSet, QueryEvaluationException> evaluate(
			TupleExpr tupleExpr, Dataset dataset, BindingSet bindings,
			boolean includeInferred) throws SailException {
//		System.out.println(tupleExpr.toString());

		TupleExpr tupleExprClone = tupleExpr.clone();
		
		//simplify the "describe"
		if (tupleExpr instanceof Reduced) {
			removeObjectDesc((Reduced) tupleExprClone);
		}
		
		//process the "limit" and "offset"
		long limit = -1;
		if (tupleExprClone instanceof Slice) {
			Slice slice = (Slice) tupleExprClone;
			limit = slice.getLimit() + slice.getOffset() + 1;
		}
		
		if (!(tupleExprClone instanceof QueryRoot))
			tupleExprClone = new QueryRoot(tupleExprClone);
		// Assign values to bound variables (precondition for QueryExtractor):
		new BindingAssigner().optimize(tupleExprClone, dataset, bindings);

		// in the original useekm code it uses "QueryExtractor" to analyze the
		// query, I have made a lot of change here. The "QueryExtractor" is not
		// used any more.
		try {
			// this is to select the best matched graph from all the indexing
			// graphs defined in the XML
			MatchedIndexedGraph graph = this.getIndexManager()
					.findBestIndexGraph(tupleExprClone);
			if (graph != null) {
				// tupleExprClone = indexer.optimize(tupleExprClone, dataset,
				// bindings);

				replaceIndexExpr(graph);
				graph.setUsedVarNames(getUsedVars(tupleExprClone));
				graph.setLimit(limit);
				
				return queryEvaluator.evaluate(this.getWrappedConnection(),
						getValueFactory(), dataset, includeInferred,
						(QueryRoot) tupleExprClone, bindings);
			} else
				return super.evaluate(tupleExprClone, dataset, bindings,
						includeInferred);
		} catch (IndexException e) {
			throw new SailException(e);
		}
	}

	private Collection<String> getUsedVars(TupleExpr expr) {
		final Collection<String> result = new HashSet<String>();
		
		expr.visitChildren(new QueryModelVisitorBase<RuntimeException>() {
			@Override
			protected void meetNode(QueryModelNode node)
					throws RuntimeException {
				result.addAll(getVarName(node));
				node.visitChildren(this);
			}
		});

		result.addAll(expr.getBindingNames());
		return result;
	}

	private static Collection<String> getVarName(QueryModelNode base) {
		Collection<String> result = new ArrayList<String>();
		if (base instanceof BinaryValueOperator) {
			Var var = null;
			var = getVarFromCompare((BinaryValueOperator) base);
			if (var != null) {
				result.add(var.getName());
			}
		} else if (base instanceof FunctionCall) {
			for (ValueExpr valueExp : ((FunctionCall) base).getArgs()) {
				if (valueExp instanceof Var && !((Var) valueExp).hasValue()) {
					result.add(((Var) valueExp).getName());
				}
			}
		} else if (base instanceof OrderElem) {
			result.add(((Var) ((OrderElem) base).getExpr()).getName());

		} else if (base instanceof StatementPattern) {
			StatementPattern pattern = (StatementPattern) base;
			if (!pattern.getSubjectVar().isAnonymous()) {
				result.add(pattern.getSubjectVar().getName());
			}
			if (!pattern.getPredicateVar().isAnonymous()) {
				result.add(pattern.getPredicateVar().getName());
			}
			if (!pattern.getObjectVar().isAnonymous()) {
				result.add(pattern.getObjectVar().getName());
			}
		}
		return result;
	}

	private static Var getVarFromCompare(BinaryValueOperator base) {
		if (base.getLeftArg() instanceof Var) {
			return (Var) base.getLeftArg();
		} else if (base.getRightArg() instanceof Var) {
			return (Var) base.getRightArg();
		}
		return null;
	}

	/**
	 * After the best matched graph has been found, we should replace all the
	 * patterns and filters with {@link IndexerExpr} or "true" boolean
	 * constants.
	 * 
	 * @param graph
	 */
	private void replaceIndexExpr(MatchedIndexedGraph graph) {
		for (FunctionCall call : graph.getFunctionCalls()) {
			call.replaceWith(new ValueConstant(valueFactory.createLiteral(true)));
		}
		for (Regex regex : graph.getRegexs()) {
			regex.replaceWith(new ValueConstant(valueFactory
					.createLiteral(true)));
		}
		for (Compare compare : graph.getCompares()) {
			compare.replaceWith(new ValueConstant(valueFactory
					.createLiteral(true)));
		}
		
		
//		if (graph.getOrders() != null && graph.getOrders() instanceof List) {
//			List<OrderElem> orders = (List<OrderElem>) graph.getOrders();
//			for (int i = graph.getOrders().size() - 1; i > -1; i--) {
//				OrderElem orderElem = orders.get(i);
//				Order order = (Order) orderElem.getParentNode();
//				order.getElements().remove(orderElem);
//				if (order.getElements().size() == 0) {
//					order.replaceWith(order.getArg());
//				}
//			}
//		}
//		
		//to optimize the "order by"  
		for (OrderElem orderElem: graph.getOrders()) {
//			orderElem.replaceWith(new ValueConstant(valueFactory
//					.createLiteral(true)));
//			Object object = orderElem.getParentNode();
			Order order = (Order) orderElem.getParentNode();
			order.getElements().remove(orderElem);
			if (order.getElements().size() == 0) {
				order.replaceWith(order.getArg());
			}
//			orderElem.re
		}

		
		
		int i = 0;
		for (StatementPattern pattern : graph.getSelectedStatements()) {
			if (i == 0) {
				pattern.replaceWith(new IndexerExpr(graph, valueFactory));
			} else {
				if (pattern.getParentNode() instanceof BinaryTupleOperator) {
					BinaryTupleOperator join = (BinaryTupleOperator) pattern.getParentNode();
					TupleExpr theOther = null;
					if (join.getLeftArg() == pattern) {
						theOther = join.getRightArg();
					} else {
						theOther = join.getLeftArg();
					}
					join.replaceWith(theOther);
				} else {
					System.out.println(pattern.getParentNode().getClass());
					throw new IndexException("no filter to replace");
				}
			}

			i++;
		}
	}

	/**
	 * Redo all the indexing from scratch. It will delete everything from the
	 * indexer repositories first.
	 * 
	 * @throws SailException
	 * @throws IndexException
	 */
	public void reindex() throws SailException, IndexException {
		this.indexManager.reindex(this.getWrappedConnection());
	}
}
