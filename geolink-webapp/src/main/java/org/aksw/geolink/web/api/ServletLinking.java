package org.aksw.geolink.web.api;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.FormParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import edu.jas.structure.StarRingElem;
import org.aksw.jena_sparql_api.geo.GeoMapSupplierUtils;
import org.aksw.jena_sparql_api.utils.TripleUtils;
import org.jgap.InvalidConfigurationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.GraphUtil;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.sparql.graph.GraphFactory;
import com.hp.hpl.jena.sparql.vocabulary.FOAF;
import com.hp.hpl.jena.util.FileManager;
import com.hp.hpl.jena.vocabulary.OWL;
import com.hp.hpl.jena.vocabulary.RDF;
import com.vividsolutions.jts.geom.Geometry;

import de.uni_leipzig.simba.data.Mapping;
import de.uni_leipzig.simba.genetics.learner.GeneticActiveLearner;
import de.uni_leipzig.simba.genetics.learner.LinkSpecificationLearner;
import de.uni_leipzig.simba.genetics.learner.SupervisedLearnerParameters;
import de.uni_leipzig.simba.genetics.learner.UnSupervisedLearnerParameters;
import de.uni_leipzig.simba.genetics.learner.UnsupervisedLearner;
import de.uni_leipzig.simba.genetics.learner.UnsupervisedLinkSpecificationLearner;
import de.uni_leipzig.simba.genetics.util.PropertyMapping;
import de.uni_leipzig.simba.io.ConfigReader;

@Service
@Path("/linking")
@Transactional
public class ServletLinking {

    @Autowired
    private Gson gson;

    @Resource(name="virtuosotarget")
    private GeoGraphFactory virtuosotarget;

    @Resource(name="virtuosoclientobject")
    private VirtuosoClientObjectFactory virtuosoclientobject;

    @Context
    private HttpServletRequest req;

    public static PropertyMapping getPropertyMapping(ConfigReader config) {
        PropertyMapping result = new PropertyMapping();
        for (String sourceProperty : config.getSourceInfo().properties) {
            for (String targetProperty : config.getTargetInfo().properties) {
                result.addStringPropertyMatch(sourceProperty, targetProperty);
            }
        }

        return result;
    }

    public UnsupervisedLinkSpecificationLearner createAutoLearner(ConfigReader config) throws InvalidConfigurationException
    {
        PropertyMapping propertyMapping = getPropertyMapping(config);

        UnSupervisedLearnerParameters params = new UnSupervisedLearnerParameters(config, propertyMapping);
        //anpassen fuer demo
        params.setGenerations(10);
        params.setPopulationSize(10);

        UnsupervisedLinkSpecificationLearner result = new UnsupervisedLearner();
        result.init(params.getConfigReader().sourceInfo,
                params.getConfigReader().targetInfo, params);

        return result;
    }


    public LinkSpecificationLearner createFeedbackLearner(ConfigReader config)
            throws InvalidConfigurationException
    {
        PropertyMapping propertyMapping = getPropertyMapping(config);

        LinkSpecificationLearner learner = new GeneticActiveLearner();// LinkSpecificationLearnerFactory.getLinkSpecificationLearner(LinkSpecificationLearnerFactory.ACTIVE_LEARNER);
        // params for the learner
        SupervisedLearnerParameters params = new SupervisedLearnerParameters(
                config, propertyMapping);
        params.setPopulationSize(20);
        params.setGenerations(100);
        params.setMutationRate(0.5f);
        params.setPreserveFittestIndividual(true);
        params.setTrainingDataSize(10);
        params.setGranularity(2);

        learner.init(config.getSourceInfo(), config.getTargetInfo(), params);

        return learner;
    }

    // @GET
    // @Produces(MediaType.APPLICATION_JSON)
    // public String getSomething(@QueryParam("id") String) {

    public static String createSparqlUpdateInsertData(Iterable<Triple> triples, String graphName) {
        String result = "Insert ";

        if(graphName != null) {
            result += "Into <" + graphName + "> ";
        }

        result += "{\n";
        for(Triple t : triples) {
            result += "  " + TripleUtils.toNTripleString(t) + "\n";
        }
        result += "}";

        return result;
    }

    public void writeMapping(Mapping mapping, Geomizer geomizer, Graph g) {

        Map<Triple, Double> tripleToScore = GeomizerFactoryLimes.mappingToTriples(mapping, OWL.sameAs.asNode());

        Map<Triple, Geometry> geomized = geomizer.geomize(tripleToScore.keySet());

        Set<Triple> triples = GeoMapSupplierUtils.geomizedToRdf(geomized);

        triples = GeoMapSupplierUtils.convertOgcToVirt(triples);


        /*
        Graph test = GraphFactory.createDefaultGraph();
        GraphUtil.add(test, triples.iterator());
        Model testm = ModelFactory.createModelForGraph(test);
        testm.write(System.out, "TURTLE");
        */
        //System.out.println(triples);


        g.clear();

        //targetgraph.remove(s, p, null);;

        //String queryString = createSparqlUpdateInsertData(triples, targetgraph.getGraphName());
        //VirtuosoUpdateRequest vur = VirtuosoUpdateFactory.create(queryString, targetgraph);


        //vur.exec();

        GraphUtil.add(g, triples.iterator());

        //close results in error. Same graph for all servlets???
        //targetgraph.close();
    }

    public static Mapping toMapping(Iterable<Triple> triples) {
        Mapping result = new Mapping();
        for(Triple t : triples) {
            Node s = t.getSubject();
            Node o = t.getObject();

            if(s.isURI() && o.isURI()) {
                result.add(s.getURI(), o.getURI(), 1.0);
            }
        }
        return result;
    }


    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/executeFromSpec")
    public String learnLinkSpec(@FormParam("spec") String spec, @FormParam("project") String project, @FormParam("username") String username) throws Exception {

        // Build ressource path
        StringBuilder graphressource = new StringBuilder();
        graphressource.append(project);
        graphressource.append("/");
        graphressource.append(username);
        graphressource.append("/geomized/");

        Graph g = virtuosotarget.getGraph(graphressource.toString());
        String retval = virtuosoclientobject.getJSON(graphressource.toString());

        ConfigReader config = gson.fromJson(spec, ConfigReader.class);
        config.afterPropertiesSet();

        System.out.println(config.toString());

        //real methods
        UnsupervisedLinkSpecificationLearner learner = createAutoLearner(config);
        Mapping mapping = learner.learn();

        //direct load over local file (for local tests)
        //System.out.println("Working Directory = " + System.getProperty("user.dir"));
        //Model model = FileManager.get().loadModel("../test-data/positive.nt");
        //Graph graph = model.getGraph();
        //Set<Triple> triples = graph.find(null, null, null).toSet();
        //triples = TripleUtils.swap(triples);
        //Mapping mapping = toMapping(triples);
        // end test methods

        Geomizer geomizer = GeomizerFactoryLimes.createGeomizer(config);
        writeMapping(mapping, geomizer, g);


        return retval;
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/evaluation")
    public String evaluate(@FormParam("evaluation") String evaluation, @FormParam("project") String project, @FormParam("username") String username) throws Exception {

        // Build ressource path for geomized graph
        StringBuilder geomized_graphressource = new StringBuilder();
        geomized_graphressource.append(project);
        geomized_graphressource.append("/");
        geomized_graphressource.append(username);
        geomized_graphressource.append("/geomized/");

        //Get graph
        Graph geomized_graph = virtuosotarget.getGraph(geomized_graphressource.toString());

        // Build ressource path eval grapg
        StringBuilder eval_graphressource = new StringBuilder();
        eval_graphressource.append(username);
        eval_graphressource.append("/eval/");

        Graph eval_graph = virtuosotarget.getGraph(eval_graphressource.toString());

        System.out.println(evaluation);
        Type type = new TypeToken<HashMap<String, Boolean>>(){}.getType();
        HashMap<String, Boolean> map = gson.fromJson(evaluation, type);

        for(String key: map.keySet()) {
            System.out.println("key: " + key + "  ----> val: " + map.get(key));
        }

        /*
        Model m = ModelFactory.createDefaultModel();

        m.add(m.createResource("http://foo"), RDF.type, FOAF.Agent);
        GraphUtil.add(evaluationGraph, g);
        */

        return gson.toJson(map);
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/learnFromMapping")
    public String learnFromMapping(@FormParam("spec") String spec,
            @FormParam("mapping") String json) throws Exception
    {
        // user methode

        // muss getrennt werden
        // spec raus
        // zusätzlich writeEvaluationData mit mapping
        //  * default auf aktuellen user
        //  * optional query auf bsp anderen user
        // lernen hier zusätzlich


        //????
        //config = new ConfigReader();
        //config.validateAndRead("/home/raven/Projects/Eclipse/LIMES/Examples/Paper/largescale/lgd-dbpedia.limes.xml");
        //System.out.println(config);
        //System.out.println("---");

        //Cache?
        //Cache cache = new MemoryCache();
        //SparqlQueryModule sqm = new SparqlQueryModule(config.getSourceInfo());
        //sqm.fillCache(cache);

        /* //das ist der learner muss in eigene Methode
        UnsupervisedLinkSpecificationLearner learner = createAutoLearner(config);
        Mapping mapping = learner.learn();
        System.out.println("Mapping SIZE: " + mapping.size());
        Metric metric = learner.terminate();
        config.metricExpression = metric.getExpression();
        config.acceptanceThreshold = metric.getThreshold();
        //String result = gson.toJson(config);
        */




        ConfigReader config = gson.fromJson(spec, ConfigReader.class);

        Type mappingType = new TypeToken<Map<String, HashMap<String, Double>>>() {}.getType();
        Map<String, HashMap<String, Double>> map = gson.fromJson(json, mappingType);

        Mapping mapping = new Mapping();
        mapping.map.putAll(map);

        LinkSpecificationLearner learner = createFeedbackLearner(config);
        Mapping next = learner.learn(mapping);

        String result = gson.toJson(next);
        return result;
    }

    /*
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/delete")
    public String deleteSomethingGet(@QueryParam("id") Long id) {
        System.out.println(virtuososerver);
        return "{}";
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/delete")
    public String deleteSomething(@FormParam("id") Long id) {
        return "{}";
    }
    */
}
