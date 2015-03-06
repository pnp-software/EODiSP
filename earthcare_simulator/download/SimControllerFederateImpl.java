/*
 * EODiSP - Earth Observation Distributed Platform
 * Copyright (C) 2005  P&P Software GmbH
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 * 
 * web:  http://www.pnp-software.com
 * mail: info@pnp-software.com
 */
package org.eodisp.earthcare.sim_controller;

import static java.util.concurrent.TimeUnit.SECONDS;
import hla.rti1516.RTIambassador;
import hla.rti1516.ResignAction;
import hla.rti1516.jlc.RtiFactoryFactory;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import net.jcip.annotations.GuardedBy;

import org.apache.log4j.Logger;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eodisp.earthcare.common.util.WrapperUtil;
import org.eodisp.earthcare.sim_controller.proxies.*;
import org.eodisp.wrapper.excel.CommandButton;
import org.eodisp.wrapper.excel.ExcelApplication;
import org.eodisp.wrapper.excel.Workbook;
import org.eodisp.wrapper.hla.EodispFederate;
import org.eodisp.wrapper.hla.ObjectClassDiscoveryListener;
import org.eodisp.wrapper.hla.ObjectClassInstance;

/**
 * Implements the <code>sim_controller</code> federate. Provides a graphical
 * user interface to the user by automating Microsoft Excel.
 * 
 * 
 * @author ibirrer
 * @version $Id:$
 */
public class SimControllerFederateImpl {

	/**
	 * Log4J logger for this class
	 * 
	 * @hidden
	 */
	private final static Logger logger = Logger.getLogger(SimControllerFederateImpl.class);

	/**
	 * Updates the <code>SimulationOverview</code> Worksheet whenever a
	 * federate updates an attribute of its <code>FederateInfo</code> object
	 * class instance.
	 * 
	 * @author ibirrer
	 * @version $Id:$
	 */
	private class FederateInfoUpdater implements FederateInfoListener {
		private final int row;

		FederateInfoUpdater(int row) {
			this.row = row + 3;
		}

		/**
		 * {@inheritDoc}
		 */
		public void execStatusUpdated(FederateInfo source, FederateInfoPassel passel, final ExecStatus newValue) {
			System.out.printf("Updated Execution status of %s to %s%n", source.getName(), newValue);
			Display.getDefault().asyncExec(new Runnable() {
				public void run() {
					simulationOverviewWs.getRange("D" + row).setValue(newValue.toString());
					if (newValue.equals(ExecStatus.SHUTTING_DOWN)) {
						if (participatingFederatesCount.get() == federatesDoneCount.incrementAndGet()) {
							simulationOverviewWs.getQuitSimulationButton().setEnabled(true);
						}
					}
				}
			});
		}

		/**
		 * {@inheritDoc}
		 */
		public void failureModeUpdated(FederateInfo source, FederateInfoPassel passel, final FailureMode newValue) {
			System.out.printf("Updated Failure Mode of %s to %s%n", source.getName(), newValue);
			Display.getDefault().asyncExec(new Runnable() {
				public void run() {
					simulationOverviewWs.getRange("E" + row).setValue(newValue.toString());
				}
			});
		}

		/**
		 * {@inheritDoc}
		 */
		public void modelVersionUpdated(FederateInfo source, FederateInfoPassel passel, final String newValue) {
			System.out.printf("Updated Version of %s to %s%n", source.getName(), newValue);

			Display.getDefault().asyncExec(new Runnable() {
				public void run() {
					simulationOverviewWs.getRange("C" + row).setValue(newValue.toString());
				}
			});
		}

		/**
		 * {@inheritDoc}
		 */
		public void nameUpdated(FederateInfo source, FederateInfoPassel passel, final String newValue) {
			System.out.printf("Updated Name of %s to %s%n", source.getName(), newValue);
			Display.getDefault().asyncExec(new Runnable() {
				public void run() {
					simulationOverviewWs.getRange("B" + row).setValue(newValue.toString());
				}
			});
		}
	}

	/**
	 * The swt shell
	 */
	private Shell shell;

	/**
	 * The Excel application
	 */
	private ExcelApplication application;

	/**
	 * The sim_controller workbook
	 */
	private Workbook workbook;

	/**
	 * the 'SimulationOverview' Worksheet
	 */
	private SimulationOverviewWorksheet simulationOverviewWs;

	/**
	 * The 'ModelConfigurationWorksheet' Worksheet
	 */
	private ModelConfigurationWorksheet modelConfigurationWs;

	/**
	 * The executor that runs the hla thread. All operations that are done on
	 * the federation should be run with this executor.
	 */
	private final ExecutorService hlaExecutor = Executors.newSingleThreadExecutor();

	/**
	 * The number of federate participating.
	 */
	private final AtomicInteger participatingFederatesCount = new AtomicInteger(0);

	/**
	 * The number of federate that have finished running.
	 */
	private final AtomicInteger federatesDoneCount = new AtomicInteger(0);

	@GuardedBy("this")
	private SimControllerFederate federate;

	@GuardedBy("this")
	private SceneCreatorPar sceneCreatorPar;

	@GuardedBy("this")
	private LidFilterPar lidFilterPar;

	@GuardedBy("this")
	private RadFilterPar radFilterPar;

	@GuardedBy("this")
	private McLwSimMainPar mcLwSimMainPar;

	@GuardedBy("this")
	private McSimMainPar mcSimMainPar;

	@GuardedBy("this")
	private LidarPar lidarPar;

	@GuardedBy("this")
	private RadarPar radarPar;

	@GuardedBy("this")
	private LidarRet1Par lidarRet1Par;

	@GuardedBy("this")
	private LwMsiLidarRadarPar lwMsiLidarRadarPar;

	@GuardedBy("this")
	private MsiRetPar msiRetPar;

	@GuardedBy("this")
	private OrbitPropagatorPar orbitPropagatorPar;

	/**
	 * Starts this federate by first starting Microsoft Excel and then
	 * initialized the RTI by running {@link #initRti()} in the {{@link #hlaExecutor}
	 * executor.
	 * 
	 * @throws Exception
	 *             Any exception that may happen during the Excel or RTI startup
	 */
	public void execute() throws Exception {
		File bundlePath = new File(System.getProperty("org.eodisp.bundle-path"));

		logger.info("Loading swt dlls...");
		System.load(new File(bundlePath, "swt-awt-win32-3232.dll").getCanonicalPath());
		System.load(new File(bundlePath, "swt-win32-3232.dll").getCanonicalPath());
		System.load(new File(bundlePath, "swt-gdip-win32-3232.dll").getCanonicalPath());
		System.load(new File(bundlePath, "swt-wgl-win32-3232.dll").getCanonicalPath());

		logger.info("Starting Microsoft Excel ...");
		Display display = new Display();
		shell = new Shell(display);
		application = new ExcelApplication(shell);
		application.setVisible(true);

		File file = new File(bundlePath, "resources/sim_controller.xls");
		if (!file.exists()) {
			throw new RuntimeException(String.format("Could not open Excel workbook: %s", file));
		}
		workbook = application.openWorkbook(file, ExcelApplication.NEVER_UPDATE_LINKS, true);
		workbook.setWindowState(Workbook.WINDOW_XL_MAXIMIZED);
		simulationOverviewWs = new SimulationOverviewWorksheet(workbook.getWorksheet("SimulationOverview"));
		modelConfigurationWs = new ModelConfigurationWorksheet(workbook.getWorksheet("ModelConfiguration"));

		// Listeners
		simulationOverviewWs.addButtonPressedListener(new SimulationOverviewWorksheetButtonListener() {

			/**
			 * Reads all parameters of the 'ModelConfiguration' Worksheet and
			 * sends them to the federation. Disables the start button after it
			 * has been clicked.
			 * 
			 * @param commandButton
			 *            the button that has been pressed (this is always the
			 *            start button)
			 */
			public void startSimulationButtonPressed(CommandButton commandButton) {
				commandButton.setEnabled(false);
				updateAllParameters();
			}

			/**
			 * Quits the simulation by achieving the <code>EODISP_STOP</code>
			 * synchroniztion point. This may only quit the simulation if all
			 * other federates have achieved this synchronization point as well.
			 * The Excel application will be closed if the the
			 * <code>sim_controller.xls</code> workbook is the last workbook
			 * that is open in the Excel application.
			 * 
			 * @param commandButton
			 *            the button that has been pressed (this is always the
			 *            quit button)
			 */
			public void quitSimulationButtonPressed(CommandButton commandButton) {
				commandButton.setEnabled(false);
				hlaExecutor.execute(new Runnable() {
					public void run() {
						quitSimulation();
					}
				});
			}
		});

		modelConfigurationWs.addButtonPressedListener(new ModelConfigurationWorksheetButtonListener() {
			/**
			 * Updates the lid_filter parameters
			 */
			public void updateLidFilterButtonPressed(CommandButton commandButton) {
				updateLidFilterParameters();
			}

			/**
			 * Updates the lidar parameters
			 */
			public void updateLidarButtonPressed(CommandButton commandButton) {
				updateLidarParameters();
			}

			/**
			 * Updates the lidar_ret1 parameters
			 */
			public void updateLidarRet1ButtonPressed(CommandButton commandButton) {
				updateLidarRet1Parameters();
			}

			public void updateLwMsiLidarRadarButtonPressed(CommandButton commandButton) {
				updateLwMsiLidarRadarParameters();
			}

			/**
			 * Updates the mc_lw_sim_main parameters
			 */
			public void updateMcLwSimMainButtonPressed(CommandButton commandButton) {
				updateMcLwSimMainParameters();
			}

			/**
			 * Updates the mc_sim_main parameters
			 */
			public void updateMcSimMainButtonPressed(CommandButton commandButton) {
				updateMcSimMainParameters();
			}

			/**
			 * Updates the msi_ret parameters
			 */
			public void updateMsiRetButtonPressed(CommandButton commandButton) {
				updateMsiRetParameters();
			}

			/**
			 * Updates the orbit_propagator parameters
			 */
			public void updateOrbitPropagatorButtonPressed(CommandButton commandButton) {
				updateOrbitPropagatorParameters();
			}

			/**
			 * Updates the rad_filter parameters
			 */
			public void updateRadFilterButtonPressed(CommandButton commandButton) {
				updateRadFilterParameters();
			}

			/**
			 * Updates the radar parameters
			 */
			public void updateRadarButtonPressed(CommandButton commandButton) {
				updateRadarParameters();
			}

			/**
			 * Updates the scene_creator parameters
			 */
			public void updateSceneCreatorButtonPressed(CommandButton commandButton) {
				updateSceneCreatorParameters();
			}
		});

		// start initRti in its own thread
		hlaExecutor.execute(new Runnable() {
			public void run() {
				initRti();
			}
		});

		// Process Excel events
		while (!shell.isDisposed()) {
			if (!display.readAndDispatch())
				display.sleep();
		}
		display.dispose();

	}

	private synchronized void initRti() {
		try {
			// Connect to the RTI
			RTIambassador rtiAmbassador = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
			/*
			 * Creates a new instance of the generated SimController federate.
			 * This class was generated by the ProxyCompiler from an HLA SOM
			 * file
			 */
			federate = new SimControllerFederate(rtiAmbassador);
			/*
			 * Creates a new FederateInfoUpdater (responsible to show federate
			 * infos on the SimulationOverview Excel Worksheet) for each newly
			 * discovered FederateInfo object class instance
			 */
			federate.addObjectClassDiscoveryListener(new ObjectClassDiscoveryListener() {
				public void objectInstanceDiscovered(ObjectClassInstance objectClassInstance) {
					if (objectClassInstance instanceof FederateInfo) {
						FederateInfo federateInfo = (FederateInfo) objectClassInstance;
						federateInfo.addFederateInfoListener(new FederateInfoUpdater(participatingFederatesCount
								.incrementAndGet()));
					} else {
						System.err.println("Discovered unknown object instance: " + objectClassInstance);
					}
				}
			});

			/*
			 * Uses the Wrapper utility convenience methos that initialized an
			 * EODiSP federate.
			 */
			WrapperUtil.eodispStart(federate);

			/*
			 * Instantiate a new object class for each parameter class that this
			 * federate can control
			 */
			sceneCreatorPar = federate.newSceneCreatorPar();
			lidFilterPar = federate.newLidFilterPar();
			radFilterPar = federate.newRadFilterPar();
			mcLwSimMainPar = federate.newMcLwSimMainPar();
			mcSimMainPar = federate.newMcSimMainPar();
			lidarPar = federate.newLidarPar();
			radarPar = federate.newRadarPar();
			lidarRet1Par = federate.newLidarRet1Par();
			lwMsiLidarRadarPar = federate.newLwMsiLidarRadarPar();
			msiRetPar = federate.newMsiRetPar();
			orbitPropagatorPar = federate.newOrbitPropagatorPar();

			/* We are ready to start the simulation -> enable the start button */
			Display.getDefault().asyncExec(new Runnable() {
				public void run() {
					simulationOverviewWs.getStartSimulationButton().setEnabled(true);
				}
			});
		} catch (Exception e) {
			fatalError(e);
		}
	}

	/**
	 * Reads the parameters from the 'ModelConfiguration' Worksheet and sends
	 * them to the federation.
	 */
	private synchronized void updateAllParameters() {
		updateOrbitPropagatorParameters();
		updateSceneCreatorParameters();
		updateLidFilterParameters();
		updateRadFilterParameters();
		updateMcLwSimMainParameters();
		updateMcSimMainParameters();
		updateLidarParameters();
		updateRadarParameters();
		updateLidarRet1Parameters();
		updateLwMsiLidarRadarParameters();
		updateMsiRetParameters();
	}

	/**
	 * Reads the parameters of the scene_creator from the 'ModelConfiguration'
	 * Worksheet and updates them in the federation.
	 */
	private synchronized void updateSceneCreatorParameters() {
		System.out.println("setting scene creator params");
		sceneCreatorPar.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getSceneCreatorFailureMode()
				.getIntValue()));
		sceneCreatorPar.setHorizontalResolution(modelConfigurationWs.getSceneCreatorHorizontalResolution()
				.getFloatValue());
		sceneCreatorPar.setXExtent(modelConfigurationWs.getSceneCreatorXExtent().getFloatValue());
		sceneCreatorPar.setYExtent(modelConfigurationWs.getSceneCreatorYExtent().getFloatValue());
		sceneCreatorPar.setZExtent(modelConfigurationWs.getSceneCreatorZExtent().getFloatValue());

		updateParams(sceneCreatorPar);
	}

	/**
	 * Reads the parameters of the lid_filter from the 'ModelConfiguration'
	 * Worksheet and updates them in the federation.
	 */
	private synchronized void updateLidFilterParameters() {
		lidFilterPar.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getLidarFailureMode().getIntValue()));
		lidFilterPar.setLaserLineWidth(modelConfigurationWs.getLidFilterLaserLineWidth().getFloatValue());
		lidFilterPar.setLaserPulseEnergy(modelConfigurationWs.getLidFilterLaserPulseEnergy().getIntValue());
		updateParams(lidFilterPar);
	}

	/**
	 * Reads the parameters of the rad_filter from the 'ModelConfiguration'
	 * Worksheet and updates them in the federation.
	 */
	private synchronized void updateRadFilterParameters() {
		radFilterPar.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getRadFilterFailureMode().getIntValue()));
		radFilterPar.setEndingAltitude(modelConfigurationWs.getRadFilterEndingAltitude().getFloatValue());
		radFilterPar.setStartingAltitude(modelConfigurationWs.getRadFilterStartingAltitude().getFloatValue());
		updateParams(radFilterPar);
	}

	/**
	 * Reads the parameters of the mc_lw_sim_main from the 'ModelConfiguration'
	 * Worksheet and updates them in the federation.
	 */
	private synchronized void updateMcLwSimMainParameters() {
		mcLwSimMainPar.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getLwMsiLidarRadarFailureMode()
				.getIntValue()));
		mcLwSimMainPar.setOutputResolution(modelConfigurationWs.getMcLwSimMainOutputResolution().getFloatValue());
		mcLwSimMainPar.setRandomNumberSeed(modelConfigurationWs.getMcLwSimMainRandomNumberSeed().getIntValue());
		updateParams(mcLwSimMainPar);
	}

	/**
	 * Reads the parameters of the mc_sim_main from the 'ModelConfiguration'
	 * Worksheet and updates them in the federation.
	 */
	private synchronized void updateMcSimMainParameters() {
		mcSimMainPar.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getMcSimMainFailureMode().getIntValue()));
		mcSimMainPar.setOutputResolution(modelConfigurationWs.getMcSimMainOutputResolution().getFloatValue());
		mcSimMainPar.setRandomNumberSeed(modelConfigurationWs.getMcSimMainRandomNumberSeed().getIntValue());
		updateParams(mcSimMainPar);
	}

	/**
	 * Reads the parameters of the lidar from the 'ModelConfiguration' Worksheet
	 * and updates them in the federation.
	 */
	private synchronized void updateLidarParameters() {
		lidarPar.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getLidarFailureMode().getIntValue()));
		lidarPar.setDetectorQuantumEfficiency(modelConfigurationWs.getLidarDetectorQuantumEfficiency().getFloatValue());
		lidarPar.setNumberOfOpticalElements(modelConfigurationWs.getLidarNumberOfOpticalElements().getIntValue());
		updateParams(lidarPar);
	}

	private synchronized void updateRadarParameters() {
		radarPar.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getRadarFailureMode().getIntValue()));
		radarPar.setPulseRepetitionFrequency(modelConfigurationWs.getRadarPulseRepetitionFrequency().getFloatValue());
		updateParams(radarPar);
	}

	/**
	 * Reads the parameters of the lidar_ret1 from the 'ModelConfiguration'
	 * Worksheet and updates them in the federation.
	 */
	private synchronized void updateLidarRet1Parameters() {
		lidarRet1Par.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getLidarRet1FailureMode().getIntValue()));
		lidarRet1Par.setHorizontalResolution(modelConfigurationWs.getLidarRet1HorizontalResolution().getFloatValue());
		lidarRet1Par.setVerticalResolution(modelConfigurationWs.getLidarRet1VerticalResolution().getFloatValue());
		updateParams(lidarRet1Par);
	}

	/**
	 * Reads the parameters of the lw_msi_lidar_radar from the
	 * 'ModelConfiguration' Worksheet and updates them in the federation.
	 */
	private synchronized void updateLwMsiLidarRadarParameters() {
		lwMsiLidarRadarPar.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getLwMsiLidarRadarFailureMode()
				.getIntValue()));
		lwMsiLidarRadarPar.setMaxIterations(modelConfigurationWs.getLwMsiLidarRadarMaxIterations().getIntValue());
		updateParams(lwMsiLidarRadarPar);
	}

	/**
	 * Reads the parameters of the msi_ret from the 'ModelConfiguration'
	 * Worksheet and updates them in the federation.
	 */
	private synchronized void updateMsiRetParameters() {
		msiRetPar.setFailureMode(FailureMode.valueOf(modelConfigurationWs.getMsiRetFailureMode().getIntValue()));
		updateParams(msiRetPar);
	}

	/**
	 * Reads the parameters of the orbit_propagator from the
	 * 'ModelConfiguration' Worksheet and updates them in the federation.
	 */
	private synchronized void updateOrbitPropagatorParameters() {
		orbitPropagatorPar.setSolarPos1(modelConfigurationWs.getOrbitPropagatorSolarPos1().getDoubleValue());
		orbitPropagatorPar.setSolarPos2(modelConfigurationWs.getOrbitPropagatorSolarPos2().getDoubleValue());
		updateParams(orbitPropagatorPar);
	}

	/**
	 * Convenience method that runs the updateAttributeValues() method in the
	 * {@link #hlaExecutor} thread.
	 * 
	 * @param instance
	 *            the object class instance to be updated.
	 */
	private void updateParams(final ObjectClassInstance instance) {
		hlaExecutor.execute(new Runnable() {
			public void run() {
				try {
					synchronized (SimControllerFederateImpl.this) {
						instance.updateAttributeValues(WrapperUtil.NULL_BYTE);
					}
				} catch (Throwable e) {
					fatalError(e);
				}
			}
		});
	}

	/**
	 * Can be called if a fatal error happens on this federate. The excel
	 * workbook will be closed after this is called.
	 * 
	 * @param e
	 *            the exception that caused the fatal error
	 */
	private void fatalError(Throwable e) {
		System.out.println("FATAL ERROR: Close workbook and exit federate");
		e.printStackTrace();
		Display.getDefault().asyncExec(new Runnable() {
			public void run() {
				workbook.close();
				shell.dispose();
				System.exit(1);
			}
		});
	}

	/**
	 * Quits the simulation by achieving the synchronization point
	 * <code>EODISP_STOP</code>. The workbook will be closed if the
	 * <code>sim_controller.xls</code> is the last workbook open in the Excel
	 * application.
	 */
	private synchronized void quitSimulation() {
		try {
			federate.achieveSyncPointAndAwaitFederationSynchronization("EODISP_STOP", Long.MAX_VALUE, SECONDS);
			federate.getRtiAmbassador().resignFederationExecution(ResignAction.UNCONDITIONALLY_DIVEST_ATTRIBUTES);
		} catch (Exception e) {
			System.err.println("Error while waiting for sync point EODISP_STOP");
		} finally {
			Display.getDefault().asyncExec(new Runnable() {
				public void run() {
					System.out.printf("Close workbook: %s", workbook);
					workbook.close();

					// Close Excel if only one workbook is
					// left
					if (application.getNrOfWorkbooks() == 0) {
						System.out.println("Exit Excel Application");
						application.quit();
					}
					shell.dispose();
				}
			});
		}
	}
}
