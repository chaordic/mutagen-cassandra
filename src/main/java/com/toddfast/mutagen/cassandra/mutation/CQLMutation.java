package com.toddfast.mutagen.cassandra.mutation;

import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.cql.CqlStatementResult;
import com.toddfast.mutagen.MutagenException;
import com.toddfast.mutagen.State;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Todd Fast
 */
public class CQLMutation extends AbstractCassandraMutation {

	private String source;
	
	private State<Integer> state;
	
	private List<String> statements=new ArrayList<String>();

	public CQLMutation(Keyspace keyspace, String resourceName) {
		super(keyspace);
		state=super.parseVersion(resourceName);
		loadCQLStatements(resourceName);
	}

	@Override
	protected String getChangeSummary() {
		return source;
	}
	
	@Override
	public State<Integer> getResultingState() {
		return state;
	}

	private void loadCQLStatements(String resourceName) {

		try {
			source=loadResource(resourceName);
		}
		catch (IOException e) {
			throw new MutagenException("Could not load resource \""+
				resourceName+"\"",e);
		}

		if (source==null) {
			// File was empty
			return;
		}

		String[] lines=source.split("\n");

		StringBuilder statement=new StringBuilder();

		for (int i=0; i<lines.length; i++) {
			int index;
			String line=lines[i];
			String trimmedLine=line.trim();

			if (trimmedLine.startsWith("--") || trimmedLine.startsWith("//")) {
				// Skip
			}
			else
			if ((index=line.indexOf(";"))!=-1) {
				// Split the line at the semicolon
				statement
					.append("\n")
					.append(line.substring(0,index+1));
				statements.add(statement.toString());
				
				if (line.length() > index+1) {
					statement=new StringBuilder(line.substring(index+1));
				}
				else {
					statement=new StringBuilder();
				}
			}
			else {
				statement
					.append("\n")
					.append(line);
			}

		}


	}


	/**
	 *
	 *
	 */
	public String loadResource(String path)
			throws IOException {

		ClassLoader loader=Thread.currentThread().getContextClassLoader();
		if (loader==null) {
			loader=getClass().getClassLoader();
		}

		InputStream input=loader.getResourceAsStream(path);
		if (input==null) {
			File file=new File(path);
			if (file.exists()) {
				input=new FileInputStream(file);
			}
		}

		if (input==null) {
			throw new IllegalArgumentException("Resource \""+
				path+"\" not found");
		}

		try {
			input=new BufferedInputStream(input);
			return loadResource(input);
		}
		finally {
			try {
				if (input!=null) {
					input.close();
				}
			}
			catch (IOException e) {
				// Ignore
			}
		}
	}


	/**
	 *
	 *
	 */
	public String loadResource(InputStream input)
			throws IOException {

		String result=null;
		int available=input.available();
		if (available > 0) {
			// Read max 64k. This is a damn lazy implementation...
			final int MAX_BYTES=65535;

			// Read all available bytes in one chunk
			byte[] buffer=new byte[Math.min(available,MAX_BYTES)];
			int numRead=input.read(buffer);

			result=new String(buffer,0,numRead,"UTF-8");
		}

		return result;
	}

	/**
	 *
	 *
	 */
	@Override
	protected void performMutation(Context context) {
		context.debug("Executing mutation {}",state.getID());
		for (String statement: statements) {
			context.debug("Executing CQL \"{}\"",statement);

			try {
				OperationResult<CqlStatementResult> result=
					getKeyspace().prepareCqlStatement()
						.withCql(statement)
						.execute();

				context.info("Successfully executed CQL \"{}\" in {} attempts",
					statement,result.getAttemptsCount());
			}
			catch (ConnectionException e) {
				context.error("Exception executing CQL \"{}\"",statement,e);
				throw new MutagenException("Exception executing CQL \""+
					statement+"\"",e);
			}
			catch (RuntimeException e) {
				context.error("Exception executing CQL \"{}\"",statement,e);
				throw e;
			}
		}
		context.debug("Done executing mutation {}",state.getID());
	}
}
