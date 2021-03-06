import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import bwapi.Game;
import bwapi.Order;
import bwapi.Pair;
import bwapi.Player;
import bwapi.TechType;
import bwapi.TilePosition;
import bwapi.Unit;
import bwapi.UnitType;
import bwapi.UpgradeType;
import java.util.logging.*;

public class Builder {
	public Logger logger = Logger.getLogger(ExampleBot.class.getName());
	private static Game game;
	private static Player me;

	// internal resource representation because we might need to "lock resources"
	// when building
	static int minerals = 0;
	static int gas = 0;
	// bad bad code
	public Commander commander;
	public ArrayList<UnitType> ownedBuildingTypes;
	public HashSet<Unit> gasExtractors;
	public ArrayList<UnitType> buildOrder;
	public HashSet<UnitType> areBeingBuilt;
	// had to give access
	public HashSet<Unit> workers;
	public HashSet<Unit> busyWorkers;
	public ArrayList<TilePosition> occupiedLocations;
	public int barrackCount = 0;

	public Builder(Game game, Player me) {
		this.game = game;
		this.me = me;
		gasExtractors = new HashSet<Unit>();
		buildOrder = new ArrayList<UnitType>();
		areBeingBuilt = new HashSet<UnitType>();
		workers = new HashSet<Unit>();
		busyWorkers = new HashSet<Unit>();
		occupiedLocations = new ArrayList<TilePosition>();
		initBuildStack();
	}

	public void setCommander(Commander comm) {
		commander = comm;
	}

	public Commander getCommander() {
		return commander;
	}

	// Returns a suitable TilePosition to build a given building type near
	// specified TilePosition aroundTile, or null if not found. (builder parameter
	// is our worker)
	public TilePosition getBuildTile(Unit builder, UnitType buildingType, TilePosition aroundTile) {
		TilePosition ret = null;
		int maxDist = 3;
		int stopDist = 40;

		if (buildingType.isAddon()) {
			for (Unit n : me.getUnits())
				if (n.canBuildAddon(buildingType) && reserveResources(buildingType))
					n.buildAddon(buildingType);
			return TilePosition.Invalid;
		}
		// Refinery, Assimilator, Extractor
		if (buildingType.isRefinery()) {
			for (Unit n : game.neutral().getUnits()) {
				if ((n.getType() == UnitType.Resource_Vespene_Geyser)
						&& (Math.abs(n.getTilePosition().getX() - aroundTile.getX()) < stopDist)
						&& (Math.abs(n.getTilePosition().getY() - aroundTile.getY()) < stopDist)) {
					occupiedLocations.add(n.getTilePosition());
					return n.getTilePosition();
				}
			}
		}

		while ((maxDist < stopDist) && (ret == null)) {
			for (int i = aroundTile.getX() - maxDist; i <= aroundTile.getX() + maxDist; i++) {
				for (int j = aroundTile.getY() - maxDist; j <= aroundTile.getY() + maxDist; j++) {
					if (game.canBuildHere(new TilePosition(i, j), buildingType, builder, false)) {
						boolean plannedByOther = false;
						for (TilePosition tile : occupiedLocations) {
							if (Math.abs(tile.getX() - i) == 0)
								plannedByOther = true;
							if (Math.abs(tile.getY() - j) == 0)
								plannedByOther = true;
						}
						if (plannedByOther)
							continue;
						// units that are blocking the tile
						boolean unitsInWay = false;
						for (Unit u : game.getAllUnits()) {
							if (u.getID() == builder.getID())
								continue;
							if ((Math.abs(u.getTilePosition().getX() - i) < 6)
									&& (Math.abs(u.getTilePosition().getY() - j) < 6))
								unitsInWay = true;
						}
						if (!unitsInWay) {
							occupiedLocations.add(new TilePosition(i, j));
							for (int ct = 0; ct < buildingType.tileWidth(); ct++)
								occupiedLocations.add(new TilePosition(i, j + ct));
							for (int ct = 0; ct < buildingType.tileHeight(); ct++)
								occupiedLocations.add(new TilePosition(i + ct, j));
							return new TilePosition(i, j);
						}
						// creep for Zerg
						if (buildingType.requiresCreep()) {
							boolean creepMissing = false;
							for (int k = i; k <= i + buildingType.tileWidth(); k++) {
								for (int l = j; l <= j + buildingType.tileHeight(); l++) {
									if (!game.hasCreep(k, l))
										creepMissing = true;
									break;
								}
							}
							if (creepMissing)
								continue;
						}
					}
				}
			}
			maxDist += 2;
		}

		if (ret == null)
			buildOrder.remove(buildingType);
		return ret;
	}

	public void trainUnit(UnitType type) {
		for (Unit u : me.getUnits())
			if (u.canTrain(type) && !u.isTraining() && minerals > type.mineralPrice() && gas > type.gasPrice()) {
				u.train(type);
				minerals -= type.mineralPrice();
				gas -= type.gasPrice();
			}
	}

	public void trainArmy() {
		if (commander.idealSquad == null)
			return;
		HashMap<UnitType, Integer> necesarryUnits = (HashMap<UnitType, Integer>) commander.idealSquad.clone();
		for (UnitType type : necesarryUnits.keySet())
			for (Unit u : commander.squad)
				if (type == u.getType()) {
					int oldValue = necesarryUnits.get(type);
					necesarryUnits.put(type, oldValue - 1);
				}
		for (UnitType type : necesarryUnits.keySet())
			if (necesarryUnits.get(type) > 0)
				trainUnit(type);
	}

	public boolean buildClose(UnitType building, TilePosition place) {
		boolean startedBuilding = false;
		for (Unit myUnit : workers) {
			if(commander.isScout(myUnit))
				continue;
			if (myUnit.getHitPoints() <= 0 || !myUnit.canMove())
				continue; // really now
			if (myUnit.getBuildType() != UnitType.None)
				continue;
			if (myUnit.isConstructing() || busyWorkers.contains(myUnit))
				continue;
			TilePosition buildTile = this.getBuildTile(myUnit, building, place);
			if (buildTile == null || buildTile == TilePosition.Invalid) {
				logger.log(Level.INFO, "Could not determine build location for building: " + building);
				break;
			}
			busyWorkers.add(myUnit);
			areBeingBuilt.add(building);
			myUnit.build(building, buildTile);
			// todo deal with what happens if a building is destroyed (it's going to remain
			// an occupied location
			logger.log(Level.INFO,
					myUnit.getID() + " is building a: " + building + "(" + myUnit.getBuildType() + ") at " + buildTile
							+ "minerals: " + me.minerals() + "(locked: " + minerals + ") stack:" + buildOrder);	
			startedBuilding = true;
			break;
		}
		return startedBuilding;
	}

	public void initBuildStack() {
		//let's not talk about this for now
	}

	public void updateStackForIdealSquad() {
		for (UnitType unit : commander.idealSquad.keySet()) {
			for (UnitType req : unit.requiredUnits().keySet()) {
				// logger.log(Level.INFO, req + " is needed for " + unit);
				if (ownedBuildingTypes.contains(req) || areBeingBuilt.contains(req))
					continue;
				addToBuildStack(req);
			}
			TechType reqTech = unit.requiredTech();
			// logger.log(Level.INFO, reqTech + " is needed for " + unit);
			if (reqTech != TechType.None && !ownedBuildingTypes.contains(reqTech))
				addToBuildStack(reqTech.requiredUnit());
			else if (!me.hasResearched(reqTech))
				startTech(reqTech);
		}
	}

	public void sendIdleMine() {
		// if it's a worker and it's idle, send it to the closest mineral patch
		for (Unit myUnit : me.getUnits()) {
			if(commander.isScout(myUnit))
				continue;
			if (myUnit.isIdle() && myUnit.getType() == UnitType.Terran_SCV) {
				Unit closestMineral = null;
				// find the closest mineral
				for (Unit neutralUnit : game.neutral().getUnits())
					if (neutralUnit.getType().isMineralField())
						if (closestMineral == null
								|| myUnit.getDistance(neutralUnit) < myUnit.getDistance(closestMineral)) {
							closestMineral = neutralUnit;

						}

				// if a mineral patch was found, send the worker to gather it
				if (closestMineral != null) {
					myUnit.gather(closestMineral, false);
					workers.add(myUnit);
					// logger.log(Level.INFO, "go mine " + myUnit.getID());
				}
			}

		}
	}

	public List<UnitType> getPreReq(UnitType myUnit) {
		ArrayList<UnitType> result = new ArrayList<>();
		Map<UnitType, Integer> req = myUnit.requiredUnits();
		result.addAll(req.keySet());
		return result;
	}

	public boolean haveBuildingOfType(UnitType type) {
		for (Unit u : me.getUnits()) {
			if (u.getType() == type && u.isCompleted())
				return true;
		}
		return false;
	}

	public boolean canBuild(UnitType type) {
		if (minerals < type.mineralPrice() || gas < type.gasPrice())
			return false;
		for (UnitType preReq : getPreReq(type)) {
			if (!haveBuildingOfType(preReq) && preReq != UnitType.None)
				return false;
		}
		return true;

	}

	public void addPreReq(UnitType type) {
		List<UnitType> preReq = getPreReq(type);
		for (UnitType preReqType : preReq) {
			// if the prerequisite is not a building ignore for now
			if (!preReqType.isBuilding())
				continue;
			// todo treat this case because it gets called in first frame and adds a command
			// center to the build order AND needs to treat the case where you're missing a
			// base
			if (preReqType == UnitType.Terran_Command_Center)
				continue;
			// if the building is in our build order, ignore it
			if (buildOrder.contains(preReqType) || haveBuildingOfType(preReqType) || areBeingBuilt.contains(preReqType))
				continue;
			logger.log(Level.INFO, "Add building: " + preReqType + " to build stack because of: " + type);
			// if the building isn't in our build order, check for its prerequisites
			addToBuildStack(preReqType);
		}
	}

	public void addToBuildStack(UnitType type) {
		if (type == UnitType.None || buildOrder.contains(type))
			return;
		buildOrder.add(type);
		addPreReq(type);
	}

	public void buildFromStack() {
		if(buildOrder.isEmpty())
			return;
		ArrayList<UnitType> toRemove = new ArrayList<>();
		for (UnitType building : buildOrder) {
			if (building == UnitType.None) {
				continue;
			}
			// if we have enough resources to build this and whatever we want to build at
			// this time
			if (canBuild(building)) {
				// game.setLocalSpeed(1);
				// if we couldn't start building
				if (!buildClose(building, me.getStartLocation())) {
					toRemove.add(building);
					continue;
				}
				addPreReq(building);
				// make sure we actually can build this
				minerals -= building.mineralPrice();
				gas -= building.gasPrice();
				toRemove.add(building);
				// might need to only do one per frame because of silly bug
				break;
			}
		}
		for(UnitType building: toRemove)
			buildOrder.remove(building);
	}

	public void startUpgrade(UpgradeType type) {
		for (Unit myUnit : me.getUnits()) {
			if (myUnit.canUpgrade(type)) {
				myUnit.upgrade(type);
				return;
			}
		}
	}

	public void upgrades() {
		for (Unit myUnit : me.getUnits()) {
			if (myUnit.getType() == UnitType.Terran_Engineering_Bay) {
				if (myUnit.isIdle() && me.getUpgradeLevel(UpgradeType.Terran_Infantry_Weapons) == 0) {
					myUnit.upgrade(UpgradeType.Terran_Infantry_Weapons);
				} else
					myUnit.upgrade(UpgradeType.Terran_Infantry_Armor);
			}
		}
	}

	public void bunker() {
		for (Unit myUnit : me.getUnits())
			if (myUnit.getType() == UnitType.Terran_Bunker)
				return;
		if (buildOrder.contains(UnitType.Terran_Bunker) || game.elapsedTime() < 60
				|| areBeingBuilt.contains(UnitType.Terran_Bunker))
			return;
		addToBuildStack(UnitType.Terran_Bunker);
		addToBuildStack(UnitType.Terran_Bunker);
		addToBuildStack(UnitType.Terran_Bunker);
	}

	public void evaluateTech() {
		boolean bHaveEngineeringBay = false;
		for (Unit myUnit : me.getUnits()) {
			if (myUnit.getType() == UnitType.Terran_Engineering_Bay)
				bHaveEngineeringBay = true;
		}
		if (!bHaveEngineeringBay && !buildOrder.contains(UnitType.Terran_Engineering_Bay)
				&& !areBeingBuilt.contains(UnitType.Terran_Engineering_Bay)
				&& !alreadyBuilding(UnitType.Terran_Engineering_Bay) && barrackCount > 1) {
			addToBuildStack(UnitType.Terran_Engineering_Bay);

		}
		boolean bHaveAcademy = false;
		for (Unit myUnit : me.getUnits()) {
			if (myUnit.getType() == UnitType.Terran_Academy)
				bHaveAcademy = true;
		}
		if (!bHaveAcademy && !buildOrder.contains(UnitType.Terran_Academy)
				&& !areBeingBuilt.contains(UnitType.Terran_Academy) && !alreadyBuilding(UnitType.Terran_Academy)) {
			addToBuildStack(UnitType.Terran_Academy);

		}
	}

	public boolean startTechBuilding() {
		return false;
	}

	public boolean reserveResources(UnitType unit) {
		if (minerals > unit.mineralPrice() && gas > unit.gasPrice()) {
			minerals -= unit.mineralPrice();
			gas -= unit.gasPrice();
			return true;
		}
		return false;
	}

	public boolean reserveResources(TechType tech) {
		if (minerals > tech.mineralPrice() && gas > tech.gasPrice()) {
			minerals -= tech.mineralPrice();
			gas -= tech.gasPrice();
			return true;
		}
		return false;
	}

	public void startTech(TechType tech) {
		for (Unit u : me.getUnits()) {
			if (!u.canResearch())
				return;
			if (u.canResearch(tech) && reserveResources(tech))
				u.research(tech);
			return;
		}
	}

	public boolean alreadyBuilding(UnitType someBuilding) {
		for (Unit myUnit : this.workers)
			if (myUnit.getBuildType() == someBuilding)
				return true;
		return false;
	}

	public void supply() {
		if (me.supplyTotal() <= me.supplyUsed() + 10 && !alreadyBuilding(UnitType.Terran_Supply_Depot)
				&& !buildOrder.contains(UnitType.Terran_Supply_Depot)
				&& !areBeingBuilt.contains(UnitType.Terran_Supply_Depot)) {
			minerals -= UnitType.Terran_Supply_Depot.mineralPrice();
			gas -= UnitType.Terran_Supply_Depot.gasPrice();
			addToBuildStack(UnitType.Terran_Supply_Depot);
		}
	}

	public void minerals() {
		// iterate through my units
		for (Unit myUnit : me.getUnits()) {
			// if there's enough minerals, train an SCV
			if (myUnit.getType() == UnitType.Terran_Command_Center && workers.size() < 20 && minerals > 50) {
				myUnit.train(UnitType.Terran_SCV);
				minerals -= 50;
			}
		}
	}
	
	public void repair() {
		for(Unit myUnit : me.getUnits()) {
			if(commander.isScout(myUnit))
				continue;
			if(myUnit.getType().isBuilding() && 
					!myUnit.isBeingConstructed() &&
					(myUnit.getHitPoints() < myUnit.getType().maxHitPoints())){
				for(Unit myUnit2 : me.getUnits())
					if(myUnit2.getOrder() == Order.Repair)
						return;
				Unit repairer = null;
				for(Unit myUnit2 : workers){
						repairer = myUnit2;
						logger.log(Level.INFO, "repairing: " + myUnit.getType() + " with " + myUnit2.getID());
						break;
				}
				if(repairer != null) {
				//workers.remove(repairer);
				repairer.repair(myUnit);
				}
			}
		}
	}

	public void extractorCheck() {
		for(Unit u: me.getUnits())
			if(u.getType() == UnitType.Terran_Refinery)
				return;
		if (gasExtractors.isEmpty() && !areBeingBuilt.contains(UnitType.Terran_Refinery)
				&& !buildOrder.contains(UnitType.Terran_Refinery))
			addToBuildStack(UnitType.Terran_Refinery);
	}

	public void factories() {
		if (me.minerals() > 300 && barrackCount < 3 && !buildOrder.contains(UnitType.Terran_Barracks)
				&& !areBeingBuilt.contains(UnitType.Terran_Barracks)) {
			addToBuildStack(UnitType.Terran_Barracks);
		}
	}

	public void mineGas() {
		List<Unit> candidateGasWorkers = new ArrayList<Unit>();
		int nrOfGasMiners = 0;
		for (Unit worker : workers) {
			if(commander.isScout(worker))
				continue;
			if (worker.isGatheringGas())
				nrOfGasMiners++;
			else if (candidateGasWorkers.size() < 2 && worker.isGatheringMinerals()) {
				candidateGasWorkers.add(worker);
			}
		}
		for (int i = 0; i < 1 - nrOfGasMiners; i++) {
			if (gasExtractors.size() == 0)
				break;
			if(candidateGasWorkers.isEmpty())
				break;
			if(candidateGasWorkers.get(i) != null)
				candidateGasWorkers.get(i).gather(gasExtractors.iterator().next());
		}

		/*
		 * Bad algo for getting workers off gas for(Unit worker:workers)
		 * if(worker.isGatheringGas() && nrOfGasMiners > 3) { worker.stop();
		 * nrOfGasMiners -= 1; }
		 */
	}

	public void lockResources() {
		for (UnitType u : areBeingBuilt) {
			if (u == UnitType.None)
				continue;

			minerals -= u.mineralPrice();
			gas -= u.gasPrice();
			// logger.log(Level.INFO, u + " is locking " + u.mineralPrice() + " minerals and
			// " + u.gasPrice() + " gas");
		}
		//game.drawTextScreen(10, 170, "remaining resources: " + minerals + " " + gas);
	}

	public void evaluateGame() {
		// this cancer timing tho
		minerals = me.minerals();
		gas = me.gas();
		updateOwnedBuildingTypes();
		updateStackForIdealSquad();
		lockResources();
		// train another worker for minerals
		minerals();
		// evaluate what buildings are being built at this point
		refreshAreBeingBuiltSet();
		supply();
		for(Unit u: me.getUnits())
			if(u.getType() == UnitType.Terran_Refinery)
				buildOrder.remove(UnitType.Terran_Refinery);	
		buildFromStack();	
		
		// worker orders
		sendIdleMine();
		extractorCheck();
		trainArmy();
		ArrayList<Unit> toRemoveBusyWorkers = new ArrayList<>();
		mineGas();
		for (Unit t : busyWorkers)
			if (t.isIdle()) {
				if(commander.isScout(t))
					continue;
				toRemoveBusyWorkers.add(t);
				workers.add(t);
				logger.log(Level.INFO, t.getID() + " is no longer busy!");
			}
		for(Unit t : toRemoveBusyWorkers) {
			busyWorkers.remove(t);
		}
		
	}

	public void refreshAreBeingBuiltSet() {
		areBeingBuilt.clear();
		for (Unit worker : me.getUnits())
			if (worker.getBuildType() != null && worker.getType() == UnitType.Terran_SCV)
				areBeingBuilt.add(worker.getBuildType());
	}

	public void updateOwnedBuildingTypes() {
		ArrayList<UnitType> res = new ArrayList<UnitType>();
		for (Unit u : me.getUnits())
			if (!res.contains(u.getType()))
				res.add(u.getType());
		ownedBuildingTypes = res;
	}
	
	public void reset() {
		commander = null;
		ownedBuildingTypes = null;
		gasExtractors = null;
		buildOrder = null;
		areBeingBuilt = null;
		workers = null;
		busyWorkers = null;
		occupiedLocations = null;
		barrackCount = 0;
	}
}
