package bughunter.bughunterserver.service.impl;

import bughunter.bughunterserver.DTO.GraphDTO;
import bughunter.bughunterserver.DTO.wrapper.EdgeDTOWrapper;
import bughunter.bughunterserver.DTO.wrapper.NodeDTOWrapper;
import bughunter.bughunterserver.dao.EdgeDao;
import bughunter.bughunterserver.dao.NodeDao;
import bughunter.bughunterserver.model.entity.Edge;
import bughunter.bughunterserver.model.entity.Node;
import bughunter.bughunterserver.service.EdgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author sean
 * @date 2019-01-23.
 */
@Service
public class EdgeServiceImpl implements EdgeService {

    public static int CROWD_WORKER_NUMBER = 100;

    @Autowired
    private EdgeDao edgeDao;

    @Autowired
    private NodeDao nodeDao;

    @Autowired
    private NodeDTOWrapper nodeDTOWrapper;

    @Autowired
    private EdgeDTOWrapper edgeDTOWrapper;

    @Override
    public Edge save(Edge edge) {
        return edgeDao.save(edge);
    }


    @Override
    public Edge getNextBugHint(String currentWindow, String nextWindow) {
        //按照number降序
        List<Edge> edgeList =
                edgeDao.findBySourceNodeAndTargetNodeOrderByNumber(currentWindow, nextWindow);
        //存在isCovered为1的Edge
        if (edgeList.stream().
                anyMatch(edge -> edge.getIsCovered() == 1)) {
            //从isCovered中选
            List<Edge> coveredEdges = edgeList.stream().
                    filter(edge -> edge.getIsCovered() == 1).collect(Collectors.toList());
            return coveredEdges.get(0);
        } else {
            //初始情况,所有number都为0
            if (edgeList.stream().allMatch(edge -> edge.getNumber() == 0)) {
                //随机选择
                return edgeList.get((int) (0 + Math.random() * (edgeList.size() - 0 + 0)));
            } else {
                //按number降序中,选择小于60%人数且最大的
                edgeList.stream().filter(edge -> edge.getNumber() <= 0.6 * CROWD_WORKER_NUMBER);
                return edgeList.get(0);
            }
        }
    }

    @Override
    public List<String> getRecommBugs(String appKey, String currentWindow, Integer isCovered) {
        //当前用户所在节点
        Node currNode = nodeDao.findByWindow(currentWindow);
        HashMap<Node, List<Edge>> map = new HashMap<>();
        //待测App所有覆盖边的目标节点
        List<Node> nodeList = nodeDao.findByAppKey(appKey);

        List<Edge> edgeList = new ArrayList<>();
        for (Node node : nodeList) {
            List<Edge> handledEdges = new ArrayList<>();
            //节点对应的有向边
            List<Edge> edges = edgeDao.findBySourceNode(node.getWindow());
            //两个节点一个方向下,只能存在一条有向边
            for (Edge edge : edges) {
                if (handledEdges.stream().noneMatch(edge1 -> edge1.getSourceNode().equals(edge.getSourceNode())
                        && edge1.getTargetNode().equals(edge.getTargetNode()))) {
                    //去环
                    if (!edge.getSourceNode().equals(edge.getTargetNode())) {
                        edgeList.add(edge);
                        handledEdges.add(edge);
                    }

                }
            }
            List<Edge> resuleEdge = new ArrayList<>();
            for (Edge e : handledEdges) {
                if (edgeList.stream().noneMatch(edge -> edge.getTargetNode().equals(e.getSourceNode())
                        && edge.getSourceNode().equals(e.getTargetNode()))) {
                    resuleEdge.add(e);
                }
            }
            map.put(node, resuleEdge);
        }

        GraphDTO graphDTO = new GraphDTO(nodeList, map, appKey);

        List<List<Node>> recommNodes = new ArrayList<>();
        //寻找测试用例/普通跳转
        List<Edge> edgesContainsTC = edgeDao.findByAppKeyAndIsCovered(appKey, isCovered);
        List<Node> nodes = new ArrayList<>();
        for (Edge edge : edgesContainsTC) {
            Node node = nodeDao.findByWindow(edge.getTargetNode());
            if (nodes.stream().noneMatch(node1 -> node1.equals(node))) {
                nodes.add(node);
            }
        }
        //当前节点到目标节点的最短路径
        for (Node n : nodes) {
            if (!n.getWindow().equals(currentWindow)) {
                int startIndex = nodeList.indexOf(nodeDao.findByWindow(currentWindow));
                int destIndex = nodeList.indexOf(n);
                List<Node> pathNodes = dijkstraTravasal(startIndex, destIndex, graphDTO);
                //排除不可达节点
                if (pathNodes.size() != 1 || pathNodes.get(0).equals(currNode)) {
                    recommNodes.add(pathNodes);
                }
            }

        }

        List<String> results = new ArrayList<>();
        StringBuffer message;
        //先是自身到自身的边
        List<Edge> selfEdges = edgeDao.findBySourceNodeAndTargetNodeAndIsCoveredOrderByNumber
                (currentWindow, currentWindow, isCovered);
        if (selfEdges != null && selfEdges.size() != 0) {
            for (Edge edge : selfEdges) {
                message = new StringBuffer(edge.toString());
                message.append(":" + currentWindow + "&" + currentWindow);
                results.add(message.toString());
            }
        }

        for (List<Node> nodePath : recommNodes) {

            //排除不可达节点
            if (nodePath.size() != 1 || nodePath.get(0).equals(currNode)) {
                List<Edge> recommEdges;
                Node sourceNode;
                Node targetNode;

                if (nodePath.size() == 1 && nodePath.get(0).equals(currNode)) {
                    //起始节点自身存在环的情况
                    sourceNode = currNode;
                    targetNode = currNode;
                } else {
                    //正常情况
                    sourceNode = nodePath.get(nodePath.size() - 2);
                    targetNode = nodePath.get(nodePath.size() - 1);
                }
                recommEdges = edgeDao.findBySourceNodeAndTargetNodeAndIsCoveredOrderByNumber(
                        sourceNode.getWindow(), targetNode.getWindow(), isCovered);
                recommEdges.stream().filter(edge -> edge.getNumber() < 0.6 * CROWD_WORKER_NUMBER);

                for (Edge e : recommEdges) {
                    message = new StringBuffer(e.toString());
                    message.append(":");
                    for (Node node : nodePath) {
                        message.append(node.getWindow() + "&");
                    }
                    results.add(message.toString());
                }

            }
        }

        if (results.size() == 0 && results == null) {
            List<Edge> edges = edgeDao.findByAppKeyAndIsCovered(appKey, isCovered);
            for (Edge e : edges) {
                message = new StringBuffer(e.toString());
                message.append(e.getSourceNode() + e.getTargetNode());
                results.add(message.toString());
            }
        }
        return results;
    }


    @Override
    public List<Edge> getEdgeBySourceNodeAndTargetNode(String sourceNode, String targetNode) {
        return edgeDao.findBySourceNodeAndTargetNodeOrderByNumber(sourceNode, targetNode);
    }

    @Override
    public List<Edge> getRecommActivities(String appKey, String currentWindow) {
        Node node = nodeDao.findByWindow(currentWindow);

        return null;
    }

    @Override
    public List<Edge> getBugEdgeBySourceNodeAndTargetNode(String currentWindow, String window) {
        return edgeDao.findBySourceNodeAndTargetNodeAndIsCoveredOrderByNumber(currentWindow, window, 1);
    }


    //设置起始节点
    public void setRoot(Node v) {
        v.setParent(null);
        v.setAdjuDist(0);
        nodeDao.save(v);
    }

    public List<Node> dijkstraTravasal(int startIndex, int destIndex, GraphDTO graphDTO) {
        Node start = graphDTO.getNodeDTOs().get(startIndex);
        Node dest = graphDTO.getNodeDTOs().get(destIndex);

        String path = "[" + dest.getWindow() + "]";

        setRoot(start);
        updateChildren(graphDTO.getNodeDTOs().get(startIndex), graphDTO);

        int shortest_length = dest.getAdjuDist();

        List<Node> result = new ArrayList<>();
        start = nodeDao.findByWindow(start.getWindow());
        dest = nodeDao.findByWindow(dest.getWindow());
        result.add(start);
        while ((dest.getParent() != null) && (!dest.equals(start))) {
            result.add(dest);
            path = "[" + dest.getParent() + "] --> " + path;
            dest = nodeDao.findByWindow(dest.getParent());
        }

        System.out.println("[" + start.getWindow() + "] to [" +
                dest.getWindow() + "] dijkstra shortest path :: " + path);
        System.out.println("shortest length::" + shortest_length);
        return result;
    }

    /**
     * 从初始节点开始递归更新邻接表
     *
     * @param v
     */
    private void updateChildren(Node v, GraphDTO graphDTO) {

        HashMap<Node, List<Edge>> map = graphDTO.getNode_edgeList_map();
        if (v == null) {
            return;
        }

        if (map.get(v) == null || map.get(v).size() == 0) {
            return;
        }
        //用来保存每个可达的节点
        List<Node> childrenList = new LinkedList<Node>();
        for (Edge e : map.get(v)) {
            Node node = nodeDao.findByWindow(e.getTargetNode());

            //如果子节点之前未知，则进行初始化，
            //把当前边的开始点默认为子节点的父节点，长度默认为边长加边的起始节点的长度，并修改该点为已经添加过，表示不用初始化
            if (!node.isKnown()) {
                node.setKnown(true);
                node.setAdjuDist(v.getAdjuDist() + e.getWeight());
                node.setParent(v.getWindow());
                nodeDao.save(node);
                childrenList.add(node);
            }

            //此时该子节点的父节点和之前到该节点的最小长度已经知道了，则比较该边起始节点到该点的距离是否小于子节点的长度，
            //只有小于的情况下，才更新该点为该子节点父节点,并且更新长度。
            int nowDist = v.getAdjuDist() + e.getWeight();
            if (nowDist >= node.getAdjuDist()) {
                continue;
            } else {
                node.setAdjuDist(nowDist);
                node.setParent(v.getWindow());
            }
        }

        //更新每一个子节点
        for (Node vc : childrenList) {
            updateChildren(vc, graphDTO);
        }
    }

    @Override
    public Edge updateEdge(Long id) {
        Edge edge = edgeDao.findOne(id);
        edge.setNumber(edge.getNumber() + 1);
        edge = edgeDao.save(edge);
        return edge;
    }
}
