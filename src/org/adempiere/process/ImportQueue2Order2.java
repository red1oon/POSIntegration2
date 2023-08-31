package org.adempiere.process;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.logging.Level;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.adempiere.exceptions.AdempiereException;
import org.apache.activemq.transport.stomp.Stomp.Headers.Subscribe;
import org.apache.activemq.transport.stomp.StompConnection;
import org.apache.activemq.transport.stomp.StompFrame;
import org.compiere.model.MBPartner;
import org.compiere.model.MBPartnerLocation;
import org.compiere.model.MDocType;
import org.compiere.model.MOrder;
import org.compiere.model.MOrderLine;
import org.compiere.model.MOrg;
import org.compiere.model.MUser;
import org.compiere.model.MWarehouse;
import org.compiere.model.Query;
import org.compiere.process.ProcessInfoParameter;
import org.compiere.process.SvrProcess;
import org.compiere.util.Env;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class ImportQueue2Order2 extends SvrProcess {
	private int cnt = 0;	
	private int count = 0;
	private static String p_Test = "N";
	private static String p_Link = "/queue/Orders";
	private static String p_LinkURL = "localhost"; 			 
	private static String trx;
	
	@Override
	protected void prepare() {
		trx = get_TrxName();
		ProcessInfoParameter[] para = getParameter();
		for (int i = 0; i < para.length; i++)
		{
			String name = para[i].getParameterName();
			
			if (name.equals("IsTest"))
				p_Test = (String)para[i].getParameter();
			else if (name.equals("Link"))
				p_Link = (String)para[i].getParameter();
			else if (name.equals("LinkURL"))
				p_LinkURL = (String)para[i].getParameter();
			else
			{
				log.log(Level.SEVERE, "Unknown Parameter: " + name);
			}
		}
	}

	@Override
	protected String doIt() throws Exception {
		String msg = "";
		StompConnection connection = new StompConnection();
		connection.open(p_LinkURL,61613); //to reference openbravo.properties in ExternalSalesHelper class
		connection.connect("","");
		connection.subscribe(p_Link, Subscribe.AckModeValues.CLIENT);
		connection.begin("MQOrders");
		while(true){
			StompFrame message = null;
			try{
				message = connection.receive();	
				log.finer(message.getBody());

				if	(parseXMLString(message.getBody())){
					log.finer("SUCCESS: Records equal to Orders");
					msg ="GOOD! "+ cnt + " XML read and orderlines created = "+ count;
				}else
					msg ="WARNING! "+ cnt + " XML read but orderlines created = "+ count;		
				
				if (p_Test.equals("N")) //if 'Y' not to acknowledge but to allow for repeat read
					connection.ack(message, "MQOrders");
			} catch(Exception e){
				if (!e.getMessage().contains("Read timed out"))
					msg = e.getMessage();
				break;
			}						
		} 		
		connection.commit("MQOrders");
		connection.disconnect();
		return msg;
	}
	

	private boolean parseXMLString(String message) throws  SAXException, ParserConfigurationException, IOException {
	 		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			//TODO check for existing ticket ID from POS
			//Local counter of imported orders 
			DocumentBuilder db = dbf.newDocumentBuilder();
		    Document doc = db.parse(new ByteArrayInputStream(message.getBytes()));
		    Element docEle = doc.getDocumentElement(); 
		    NodeList records = docEle.getElementsByTagName("detail"); 
		    
		    int AD_Org_ID_holder = 0;//check change of AD_Org_ID for new MOrder
	        int checkOrgID = 0;
	        MOrder order = null;
	        
		    for(int i = 0 ; i < records.getLength();i++) {
				//check for detail = POS Order only. Other data will be handle in later brackets
		        if (!records.item(i).getFirstChild().getTextContent().equals("POS Order"))
		        	continue;
		        cnt++;
		        //init models
		        MOrder existing = null;
		        MOrderLine orderline = null;
		        String docno = "";
		        //write to Orders
		        NodeList details = records.item(i).getChildNodes();
		        
		        //check change of AD_Org_ID for new MOrder
		        Node cn = details.item(2);//AD_Org_ID is in the 3rd XML tag
		        String checkNodeString = cn.getNodeName();
		        if (!checkNodeString.equals("AD_Org_ID"))
		        	throw new AdempiereException("ERROR! .. Can't find AD_Org_ID but found : "+checkNodeString);
		        checkOrgID = Integer.parseInt(cn.getTextContent());
		        if (checkOrgID!=AD_Org_ID_holder){ //make new MOrder
			        order = new MOrder(Env.getCtx(),0,trx);
			        log.info(checkNodeString+" has new = "+checkOrgID);
			        AD_Org_ID_holder=checkOrgID;
		        }//check change of AD_Org_ID for new MOrder
		        if (order==null)
		        	throw new AdempiereException("MOrder model is null.");
		        
		        for(int j = 0 ; j < details.getLength();j++) {
			   		Node n = details.item(j);
			   		String column = n.getNodeName();
			   		if (column.equals("DocTypeName")){
			   			if (!n.getTextContent().equals("POS Order"))
			   				break;
			   		} 
			   		else if (column.equals("AD_Org_ID")) {
			   			MOrg org = new Query(Env.getCtx(),MOrg.Table_Name,MOrg.COLUMNNAME_AD_Org_ID+"=?",get_TrxName())
			   					.setParameters(Integer.parseInt(n.getTextContent()))
			   					.setClient_ID()
			   					.first();		
			   			if (org==null)
			   				throw new AdempiereException("Importing into wrong Client or Org");
			   			order.setAD_Org_ID(Integer.parseInt(n.getTextContent()));
			   		}
			   		else  if (column.equals(MOrder.COLUMNNAME_DateOrdered)) {
			   			order.setDateOrdered(Timestamp.valueOf(n.getTextContent()));
			   		}
			   		else if (column.equals("AD_Org_ID")){
			   			order.setAD_Org_ID(Integer.parseInt(n.getTextContent()));
			   		}
			   		else  if (column.equals("POSLocatorName")) {
		    			MWarehouse warehouse = new Query(Env.getCtx(), MWarehouse.Table_Name, 
		    					"name = trim(?)", get_TrxName()).setParameters(n.getTextContent()).first();
		    			order.setM_Warehouse_ID(warehouse.getM_Warehouse_ID());
			   		}
			   		else  if (column.equals(MOrder.COLUMNNAME_DateOrdered)) {
			   			order.setDateOrdered(Timestamp.valueOf(n.getTextContent()));
			   		}
			   		else if (column.equals("BPartner")){
			   			MBPartner partner = null;
			   			if (n.getTextContent().isEmpty() || n.getTextContent()==null){
			   				partner = new Query(Env.getCtx(),MBPartner.Table_Name,MBPartner.COLUMNNAME_Name+"=?",trx)
				   					.setParameters("Standard")
				   					.setClient_ID()
				   					.first();
			   			}else
			   				partner = new Query(Env.getCtx(),MBPartner.Table_Name,MBPartner.COLUMNNAME_Name+"=?",trx)
		   					.setParameters(n.getTextContent()).setClient_ID().first();
			   			
			   			order.setC_BPartner_ID(partner.get_ID());
			   			//SHIP TO ADDRESS
			   			MBPartnerLocation[] location = MBPartnerLocation.getForBPartner(Env.getCtx(),partner.get_ID(),trx);
			   			order.setC_BPartner_Location_ID(location[0].getC_BPartner_Location_ID());
			   			order.setShip_Location_ID(location[0].getC_BPartner_Location_ID());
	    			}
			   		else if (column.equals("UserName")){
			   			MUser user = new Query(Env.getCtx(),MUser.Table_Name,MUser.COLUMNNAME_Name+"=?",trx)
			   					.setParameters(n.getTextContent()).setClient_ID().first();
			   			if (user!=null)
			   				order.setSalesRep_ID(user.get_ID());
			   		}
			   		//THIS IS AT ORDERLINE LEVEL
			   		else  if (column.equals(MOrder.COLUMNNAME_DocumentNo)) {
			   			docno = n.getTextContent();
			   		}
			   		else  if (column.equals("MachineName")) {
			   			docno=docno+" POS:"+n.getTextContent();
			   			//CHECK THAT ORDER EXIST BEFORE OR GET ORDER HEADER	 
			   			existing = new Query(Env.getCtx(),MOrder.Table_Name,MOrder.COLUMNNAME_C_BPartner_ID+"=? AND AD_Org_ID = ? AND DOCSTATUS = 'IP'",trx)
			   					.setParameters(order.getC_BPartner_ID(),order.getAD_Org_ID())
			   					.setClient_ID().first();
			   			if (existing!=null){ 
			   				log.fine("Same Order - BP = "+order.getC_BPartner().getName()+", Org = "+order.getAD_Org_ID()+" IDs check:"+checkOrgID+" and "+AD_Org_ID_holder);
			   				order = existing;
			   				//CHECK THAT ORDERLINE IS NOT ALREADY IMPORTED BEFORE (DOCNO IS TAKEN FROM UUID TICKET+LINENO+MACHINENO)
			   				orderline = new Query(Env.getCtx(),MOrderLine.Table_Name,
			   						MOrderLine.COLUMNNAME_Description+"=?",trx)
			   						.setParameters(docno)
			   						.setClient_ID()
			   						.first();
			   				if (orderline!=null)
			   					break; //THIS HAS BEEN IMPORTED BEFORE SO BREAK OUT TO NEXT RECORD   				
			   			}else{			   				
				   			order.setC_DocTypeTarget_ID(MDocType.DOCSUBTYPESO_POSOrder);//DocBaseType = SOO, SubTypeSO = WR
					        order.setDocStatus(MOrder.DOCSTATUS_InProgress);
					        order.setDeliveryRule(MOrder.DELIVERYRULE_Force);
				   			order.saveEx(trx); 
			   			}

			   			orderline = new MOrderLine(order);
			   			//setting description as the 'ID' of the record line = doc-no + machine-name 
			   			orderline.setDescription(docno); 
			   		}
			   		else  if (column.equals(MOrderLine.COLUMNNAME_M_Product_ID)) {			   		
			   			orderline.setM_Product_ID(Integer.parseInt(n.getTextContent()));
			   			orderline.saveEx(trx);
			   		}
			   		else  if (column.equals(MOrderLine.COLUMNNAME_QtyOrdered)) {
			   			orderline.setQty(new BigDecimal(n.getTextContent()));
			   		}
			   		else if (column.equals("paymentType")){
			   			if (n.getTextContent().equals("debt"))
			   				order.setC_DocTypeTarget_ID(MDocType.DOCSUBTYPESO_OnCreditOrder);
			   		}
			   			
			   		else  if (column.equals(MOrderLine.COLUMNNAME_PriceActual)) {
			   			orderline.setPrice();//trigger calculation
			   			orderline.setPrice(new BigDecimal(n.getTextContent()));
			   			orderline.saveEx(trx);

			   			count++;
			   		}			       
		    	}
		        order.saveEx(trx);
        		log.fine("saving MOrder: ID="+order.get_ID());
		    }
		 return (records.getLength()==count);
	}
}
