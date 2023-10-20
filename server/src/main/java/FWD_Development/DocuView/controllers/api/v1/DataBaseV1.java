package FWD_Development.DocuView.controllers.api.v1;


/* CUSTOM ADDED LIBS */
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.Iterator;
import java.util.HashMap;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
/* CUSTOM ADDED LIBS */

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSetMetaData;

import org.springframework.dao.EmptyResultDataAccessException;

// FILTERS
//      file_creation
//      document_type
//      file_name
//      customer_name
//      auction_type
//      proposal_type
//      project_type
//      commitment_date_start
//      commitment_date_end
//      auction_date_start
//      auction_date_end
//      proposal_date_start
//      proposals_date_end

// All this stuff, evil.
// better impletion form, make tree that maps out all TABLES and has data of every table, THEN use that to navigate the database

// @CrossOrigin(origins = "http://localhost:3000") // Default React port
@RestController
@RequestMapping("/api/v1/database")
public class DataBaseV1 implements Hardcoded{

	@Autowired
    	private JdbcTemplate jdbcTemplate;
    	
	private static final ObjectMapper objMapper = new ObjectMapper();
	
	private static ObjectNode resultSetToJson(ResultSet resultSet) throws SQLException {
		ObjectNode out = objMapper.createObjectNode();
		ResultSetMetaData rsmd = resultSet.getMetaData();
		for (int i = 1; i <= rsmd.getColumnCount(); i++){
			out.put(rsmd.getColumnName(i), resultSet.getString(rsmd.getColumnName(i)));
		}
		return out;
	}
	
	private ObjectNode flattenQuery(ObjectNode obj, DataBaseNode table){
		ObjectNode out = objMapper.createObjectNode();
		Map<String, DataBaseNode> connectedNodes = table.getConnected();
		Iterator<Map.Entry<String,JsonNode>> it = obj.fields();
		while (it.hasNext()){
			Map.Entry<String,JsonNode> set = it.next();
			String key = set.getKey();
			String value = set.getValue().asText();
			if ( !connectedNodes.containsKey(set.getKey()) ){ out.put(key, value); continue; }
			DataBaseNode targetTable = connectedNodes.get(key);
			String format = "SELECT * FROM %s WHERE CAST(%s AS CHAR) = \'%s\'";
			//String format = "SELECT * FROM %s WHERE %s IN (\"%s\");"; \\ slower by around 600
			String query = String.format(
						format, 
						targetTable.getName(),
						targetTable.getPrimaryKey(),
						value
					);
			// use list to handle unexpected straglers (should be impossible but you never know)
			List<ObjectNode> holder = jdbcTemplate.query(query, new RowMapper<ObjectNode>() {
					public ObjectNode mapRow(ResultSet rs, int rowNum) throws SQLException {
						return resultSetToJson(rs);
					}});
			ObjectNode append = flattenQuery(holder.get(0), connectedNodes.get(key));
			out.set(key, append);
		}
		return out;
	}
	
	
	// single string implementation ( works with a max depth of 32 )
	private void filterDataBase(DataBaseNode node, Map<String,String> allRequestParams, Map<String, String> _tableQueries) {
        if (node != null) {
            Map<String, DataBaseNode> connectedNodes = node.getConnected();
			List<String> strLst = new ArrayList<>();
            for (Map.Entry<String, DataBaseNode> set: connectedNodes.entrySet()) {
				DataBaseNode connectedNode = set.getValue();
                filterDataBase(connectedNode, allRequestParams, _tableQueries);
				
				if (_tableQueries.containsKey(connectedNode.getName())){
					String query = String.format(
						"SELECT %s FROM %s WHERE %s", 
						connectedNode.getPrimaryKey(),
						connectedNode.getName(),
						_tableQueries.get(connectedNode.getName())
					);
					//List<String> holder = jdbcTemplate.query(query, new RowMapper<String>() {
					//public String mapRow(ResultSet rs, int rowNum) throws SQLException {
					//	return rs.getString(1);
					//}});
					query = set.getKey() + " IN (" + query + ")";
					strLst.add(query);
					_tableQueries.remove(connectedNode.getName());
				};
			}

			if (!targetMap.containsKey(node.getName())) { return ; }
			String query;
			for (Filter filter : targetMap.get(node.getName())){
				if (!allRequestParams.containsKey(filter.getName())){ continue; }
				String primaryKeyColumn = node.getConnectedId(filter.getTargetId()).getPrimaryKey();
				query = String.format(
						"SELECT %s FROM %s WHERE %s", 
						primaryKeyColumn,
						filter.getOriginTable(),
						filter.filteringQueryCondition(allRequestParams.get(filter.getName()))
					);
				//List<String> holder = jdbcTemplate.query(query, new RowMapper<String>() {
				//	public String mapRow(ResultSet rs, int rowNum) throws SQLException {
				//		return rs.getString(1);
				//	}});
				query = filter.getTargetId() + " IN (" + query + ")";
				strLst.add(query);
			}
			if ( !strLst.isEmpty() ) { _tableQueries.put(node.getName(), String.join(" AND ", strLst)); }
        }
    }


	@GetMapping("")
	public JsonNode getDocs(@RequestParam Map<String,String> allRequestParams){
		ArrayNode outerArray = objMapper.createArrayNode();
		Map<String, String> tableQueries = new HashMap<>();
		DataBaseNode root = dataBaseTree.getRoot();
		filterDataBase(root, allRequestParams, tableQueries);
		String query = String.format(
						"SELECT * FROM %s WHERE %s;", 
						root.getName(),
						tableQueries.get(root.getName())
					);
		List<ObjectNode> holder = jdbcTemplate.query(query, new RowMapper<ObjectNode>() {
					public ObjectNode mapRow(ResultSet rs, int rowNum) throws SQLException {
						return resultSetToJson(rs);
					}});
		// Timed aprox avg of this section : 100 ms			
		
		for (ObjectNode json : holder){
			// outerArray.add( json ); 
			outerArray.add( flattenQuery(json, root) );
			// Timed aprox avg of this section : 700 ms
		}
		// total run time aprox avg : 800 ms
		return outerArray;
	}
	
};
