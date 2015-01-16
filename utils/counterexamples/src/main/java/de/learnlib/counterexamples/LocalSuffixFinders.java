/* Copyright (C) 2013 TU Dortmund
 * This file is part of LearnLib, http://www.learnlib.de/.
 * 
 * LearnLib is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License version 3.0 as published by the Free Software Foundation.
 * 
 * LearnLib is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with LearnLib; if not, see
 * <http://www.gnu.de/documents/lgpl.en.html>.
 */
package de.learnlib.counterexamples;

import net.automatalib.automata.concepts.SuffixOutput;
import de.learnlib.acex.analyzers.AcexAnalyzers;
import de.learnlib.api.AccessSequenceTransformer;
import de.learnlib.api.MembershipOracle;
import de.learnlib.api.Query;

/**
 * A collection of suffix-based local counterexample analyzers.
 * 
 * @see LocalSuffixFinder
 * 
 * @author Malte Isberner 
 */
public abstract class LocalSuffixFinders {
	
	/**
	 * Searches for a distinguishing suffixes by checking for counterexample yielding
	 * access sequence transformations in linear ascending order.
	 * @see #findLinear(Query, AccessSequenceTransformer, SuffixOutput, MembershipOracle)
	 */
	public static final LocalSuffixFinder<Object,Object> FIND_LINEAR
		= new AcexLocalSuffixFinder(AcexAnalyzers.LINEAR_FWD, true, "FindLinear");
	
	/**
	 * Searches for a distinguishing suffixes by checking for counterexample yielding
	 * access sequence transformations in linear descending order.
	 * @see #findLinearReverse(Query, AccessSequenceTransformer, SuffixOutput, MembershipOracle)
	 */
	public static final LocalSuffixFinder<Object,Object> FIND_LINEAR_REVERSE
		= new AcexLocalSuffixFinder(AcexAnalyzers.LINEAR_BWD, true, "FindLinear-Reverse");
	
	/**
	 * Searches for a distinguishing suffixes by checking for counterexample yielding
	 * access sequence transformations using a binary search, as proposed by Rivest &amp; Schapire.
	 * @see #findRivestSchapire(Query, AccessSequenceTransformer, SuffixOutput, MembershipOracle)
	 */
	public static final LocalSuffixFinder<Object,Object> RIVEST_SCHAPIRE
		= new AcexLocalSuffixFinder(AcexAnalyzers.BINARY_SEARCH, true, "RivestSchapire");

	
	/**
	 * Searches for a distinguishing suffixes by checking for counterexample yielding
	 * access sequence transformations in linear ascending order.
	 * 
	 * @param ceQuery the initial counterexample query
	 * @param asTransformer the access sequence transformer
	 * @param hypOutput interface to the hypothesis output, for checking whether the oracle output
	 * contradicts the hypothesis
	 * @param oracle interface to the SUL
	 * @return the index of the respective suffix, or <tt>-1</tt> if no
	 * counterexample could be found
	 * @see LocalSuffixFinder
	 */
	public static <S,I,D> int findLinear(Query<I,D> ceQuery,
			AccessSequenceTransformer<I> asTransformer,
			SuffixOutput<I,D> hypOutput,
			MembershipOracle<I, D> oracle) {
		
		return AcexLocalSuffixFinder.findSuffixIndex(AcexAnalyzers.LINEAR_FWD, true, ceQuery, asTransformer, hypOutput, oracle);
	}
	
	/**
	 * Searches for a distinguishing suffixes by checking for counterexample yielding
	 * access sequence transformations in linear descending order.
	 * 
	 * @param ceQuery the initial counterexample query
	 * @param asTransformer the access sequence transformer
	 * @param hypOutput interface to the hypothesis output, for checking whether the oracle output
	 * contradicts the hypothesis
	 * @param oracle interface to the SUL
	 * @return the index of the respective suffix, or <tt>-1</tt> if no
	 * counterexample could be found
	 * @see LocalSuffixFinder
	 */
	public static <I,D> int findLinearReverse(Query<I,D> ceQuery,
			AccessSequenceTransformer<I> asTransformer,
			SuffixOutput<I,D> hypOutput,
			MembershipOracle<I, D> oracle) {
		
		return AcexLocalSuffixFinder.findSuffixIndex(AcexAnalyzers.LINEAR_BWD, true, ceQuery, asTransformer, hypOutput, oracle);
	}
	
	
	/**
	 * Searches for a distinguishing suffixes by checking for counterexample yielding
	 * access sequence transformations using a binary search, as proposed by Rivest &amp; Schapire.
	 * 
	 * @param ceQuery the initial counterexample query
	 * @param asTransformer the access sequence transformer
	 * @param hypOutput interface to the hypothesis output, for checking whether the oracle output
	 * contradicts the hypothesis
	 * @param oracle interface to the SUL
	 * @return the index of the respective suffix, or <tt>-1</tt> if no
	 * counterexample could be found
	 * @see LocalSuffixFinder
	 */
	public static <I,D> int findRivestSchapire(Query<I,D> ceQuery,
			AccessSequenceTransformer<I> asTransformer,
			SuffixOutput<I,D> hypOutput,
			MembershipOracle<I, D> oracle) {

		return AcexLocalSuffixFinder.findSuffixIndex(AcexAnalyzers.BINARY_SEARCH, true, ceQuery, asTransformer, hypOutput, oracle);
	}
	
	@SuppressWarnings("unchecked")
	public static LocalSuffixFinder<Object, Object>[] values() {
		return new LocalSuffixFinder[]{
				FIND_LINEAR,
				FIND_LINEAR_REVERSE,
				RIVEST_SCHAPIRE
		};
	}
	
	// Prevent inheritance
	private LocalSuffixFinders() {}
}