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
package org.eodisp.earthcare.orbit_propagator;

import hla.rti1516.*;
import hla.rti1516.jlc.NullFederateAmbassador;
import hla.rti1516.jlc.RtiFactoryFactory;
import net.jcip.annotations.GuardedBy;
import net.jcip.annotations.ThreadSafe;

import org.apache.log4j.Logger;
import org.eodisp.earthcare.common.util.WrapperUtil;
import org.eodisp.earthcare.orbit_propagator.proxies.*;
import org.eodisp.wrapper.hla.EodispFederate;
import org.eodisp.wrapper.hla.ObjectClassDiscoveryListener;
import org.eodisp.wrapper.hla.ObjectClassInstance;

/**
 * rad_filter federate implementation.
 * 
 * @author ibirrer
 * @version $Id:$
 */
@ThreadSafe
public class OrbitPropagatorFederateImpl implements ObjectClassDiscoveryListener {
	/**
	 * Log4J logger for this class
	 * 
	 * @hidden
	 */
	private final static Logger logger = Logger.getLogger(OrbitPropagatorFederateImpl.class);

	@GuardedBy("this")
	private OrbitPropagatorOut orbitPropagatorOut;

	@GuardedBy("this")
	private FederateInfo federateInfo;

	@GuardedBy("this")
	private OrbitPropagatorFederate federate;

	/**
	 * Starts this federate. Prints the stack trace and exits with an exit code
	 * of <code>1</code> if anything goes wrong.
	 */
	public synchronized void execute() {
		try {
			RTIambassador rtiAmbassador = RtiFactoryFactory.getRtiFactory().getRtiAmbassador();
			federate = new OrbitPropagatorFederate(rtiAmbassador);
			federate.addObjectClassDiscoveryListener(this);
			federate.getFederateAmbassadorDelegator().registerDelegate(new NullFederateAmbassador() {
				@Override
				public void announceSynchronizationPoint(String label, byte[] userSuppliedTag)
						throws FederateInternalError {
					if (label.equals(EodispFederate.EODISP_STOP)) {
						try {
							federate.getRtiAmbassador().synchronizationPointAchieved(label);
						} catch (RTIexception e) {
							WrapperUtil.fatalError(e, federate);
						}
					}
				}

				@Override
				public void federationSynchronized(String label) throws FederateInternalError {
					if (label.equals(EodispFederate.EODISP_STOP)) {
						try {
							federate.getRtiAmbassador().resignFederationExecution(
									ResignAction.UNCONDITIONALLY_DIVEST_ATTRIBUTES);
							System.exit(0);
						} catch (RTIexception e) {
							WrapperUtil.fatalError(e, federate);
						}
					}
				}
			});
			WrapperUtil.eodispStart(federate);
			federateInfo = federate.newFederateInfo();
			federateInfo.setName("orbit_propagator");
			federateInfo.setModelVersion("1.0.0");
			orbitPropagatorOut = federate.newOrbitPropagatorOut();
			updateExecStatus(ExecStatus.READY);
			while (true) {
				try {
					this.wait();
				} catch (InterruptedException e) {
					// ignore spurious wakeups
				}
			}
		} catch (Exception e) {
			WrapperUtil.fatalError(e, federate);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public void objectInstanceDiscovered(ObjectClassInstance objectClassInstance) {
		if (objectClassInstance instanceof OrbitPropagatorPar) {
			OrbitPropagatorPar radFilterPar = (OrbitPropagatorPar) objectClassInstance;
			radFilterPar.addOrbitPropagatorParPasselListener(new OrbitPropagatorParPasselListener() {
				public void passelUpdated(OrbitPropagatorPar source, OrbitPropagatorParPassel passel) {
					calculateAndUpdateValues(passel);
				}
			});
		}
	}

	/**
	 * @param passel
	 */
	@SuppressWarnings("boxing")
	private synchronized void calculateAndUpdateValues(OrbitPropagatorParPassel passel) {
		double radianPos1 = passel.getSolarPos1();
		double radianPos2 = passel.getSolarPos2();

		double degreePos1 = Math.toDegrees(radianPos1);
		double degreePos2 = Math.toDegrees(radianPos2);

		orbitPropagatorOut.setSolarPos1(degreePos1);
		orbitPropagatorOut.setSolarPos2(degreePos2);
		try {
			logger.info(String.format("Convert: (%f,%f) -> (%f,%f)", radianPos1, radianPos2, degreePos1, degreePos2));
			orbitPropagatorOut.updateAttributeValues(WrapperUtil.NULL_BYTE);
		} catch (RTIexception e) {
			WrapperUtil.fatalError(e, federate);
		}
	}

	/**
	 * Convenience method to access the federate info guarded by the lock of
	 * this class.
	 * 
	 * @param pExecStatus
	 *            the new attribute value
	 * @exception RTIexception
	 *                The new exec status could not be delivered to the
	 *                federation
	 */
	private synchronized void updateExecStatus(ExecStatus execStatus) throws RTIexception {
		federateInfo.setExecStatus(execStatus, WrapperUtil.NULL_BYTE);
	}
}
